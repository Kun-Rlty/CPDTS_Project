<#
.SYNOPSIS
    CPDTS local database helper (development machines only).

.DESCRIPTION
    Loads your LOCAL, gitignored .env.backend file into the process environment
    WITHOUT printing any values, then runs one of:

        -Task info      -> mvn flyway:info       (read-only migration status)
        -Task validate  -> mvn flyway:validate   (read-only checksum validation)
        -Task migrate   -> mvn flyway:migrate    (applies pending migrations)
        -Task test      -> psql constraint test suite (cpdts_test only, rolled back)

    Database selection:
        -Database test  -> cpdts_test  (disposable; default)
        -Database dev   -> cpdts_dev  (local development)

    Safety:
        * The password is read from .env.backend and passed to the child process
          environment only. It is never printed, logged, committed or requested
          through chat/prompts.
        * -Task test refuses to run against anything except cpdts_test.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run_db.ps1 -Task info -Database test
    powershell -ExecutionPolicy Bypass -File scripts\run_db.ps1 -Task migrate -Database test
    powershell -ExecutionPolicy Bypass -File scripts\run_db.ps1 -Task test -Database test
    powershell -ExecutionPolicy Bypass -File scripts\run_db.ps1 -Task migrate -Database dev
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('info', 'validate', 'migrate', 'test')]
    [string]$Task,

    [ValidateSet('test', 'dev')]
    [string]$Database = 'test'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env.backend'

function Fail([string]$message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $envFile)) {
    Fail ".env.backend not found at $envFile. Copy .env.backend.example to .env.backend and fill in your DB_USER / DB_PASSWORD LOCALLY (never share or commit them)."
}

# Load KEY=VALUE pairs from the local .env.backend WITHOUT printing any values.
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { return }
    $parts = $line -split '=', 2
    if ($parts.Count -eq 2) {
        Set-Item -Path ("env:" + $parts[0].Trim()) -Value $parts[1].Trim()
    }
}

if (-not $env:DB_USER) { Fail 'DB_USER is missing in .env.backend.' }
if (-not $env:DB_PASSWORD) { Fail 'DB_PASSWORD is missing in .env.backend.' }
if ($env:DB_PASSWORD -eq 'REPLACE_LOCALLY_DO_NOT_COMMIT') {
    Fail 'DB_PASSWORD in .env.backend is still the placeholder. Edit the file locally and set your real password there (and only there).'
}

$dbHost = if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }
$dbPort = if ($env:DB_PORT) { $env:DB_PORT } else { '5432' }
$dbName = if ($Database -eq 'dev') { $env:DB_NAME } else { $env:DB_TEST_NAME }
if (-not $dbName) { Fail 'DB_NAME / DB_TEST_NAME missing in .env.backend.' }
if ($dbName -eq 'cpdts') {
    Fail 'DB_NAME is the legacy value "cpdts". Set DB_NAME=cpdts_dev in .env.backend. A database named cpdts must never be created, modified or dropped by these scripts.'
}

if ($Task -eq 'test') {
    if ($Database -ne 'test') {
        Fail 'Constraint tests are only allowed against the disposable test database (-Database test).'
    }
    $psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
    if (-not (Test-Path $psql)) {
        $cmd = Get-Command psql -ErrorAction SilentlyContinue
        if (-not $cmd) { Fail 'psql.exe not found (expected in C:\Program Files\PostgreSQL\18\bin).' }
        $psql = $cmd.Source
    }
    $testFile = Join-Path $root 'tests\database\migration_constraint_tests.sql'
    if (-not (Test-Path $testFile)) { Fail "Test file not found: $testFile" }

    # PGPASSWORD is set for this process only and never displayed.
    $env:PGPASSWORD = $env:DB_PASSWORD
    Write-Host "Running CPDTS constraint tests against database: $dbName (password loaded from local .env.backend, never displayed)"
    & $psql -h $dbHost -p $dbPort -U $env:DB_USER -d $dbName -v ON_ERROR_STOP=1 -f $testFile
    $code = $LASTEXITCODE
    Remove-Item -Path env:PGPASSWORD -ErrorAction SilentlyContinue
    exit $code
}

# Tasks: info / validate / migrate via the Flyway Maven plugin.
$env:CPDTS_DB_URL = "jdbc:postgresql://${dbHost}:${dbPort}/${dbName}"
$env:CPDTS_DB_USER = $env:DB_USER
$env:CPDTS_DB_PASSWORD = $env:DB_PASSWORD

$pom = Join-Path $root 'backend\pom.xml'
if (-not (Test-Path $pom)) { Fail "Maven pom not found: $pom" }

Write-Host "Running mvn flyway:$Task against database: $dbName (password loaded from local .env.backend, never displayed)"
& mvn -f $pom "flyway:$Task"
exit $LASTEXITCODE
