<#
.SYNOPSIS
  Creates a local-only test CA, server certificate and private key for the
  CPDTS HTTPS reverse proxy.

.DESCRIPTION
  Files are written outside the repository (default: %TEMP%\opencode) and are
  never committed. The server certificate has SAN DNS:localhost and
  IP:127.0.0.1. A fresh CA is created for this directory; delete the directory
  when no longer needed.

  The automated browser run verifies the certificate chain and uses
  Playwright's ignoreHTTPSErrors setting for the local test origin. To browse
  normally, an operator must explicitly trust ca.crt in the current user's
  Trusted Root store (see the printed instructions).

.PARAMETER OutDir
  Directory for ca.crt, ca.key, server.crt and server.key.

.PARAMETER Days
  Certificate validity in days.
#>
[CmdletBinding()]
param(
    [string]$OutDir = (Join-Path $env:TEMP 'opencode\cpdts-https-local'),
    [int]$Days = 30
)

$ErrorActionPreference = 'Stop'
if ($Days -lt 1 -or $Days -gt 365) {
    throw 'Days must be between 1 and 365.'
}

$openssl = if ($env:CPDTS_OPENSSL) { $env:CPDTS_OPENSSL } else { (Get-Command openssl.exe -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $openssl -PathType Leaf)) {
    throw "OpenSSL not found at $openssl. Set CPDTS_OPENSSL to its full path."
}

# A machine-wide OPENSSL_CONF may point at a missing vendor file (for example a
# PostgreSQL psqlODBC path). Do not let that unrelated setting break this script.
if ($env:OPENSSL_CONF -and -not (Test-Path -LiteralPath $env:OPENSSL_CONF -PathType Leaf)) {
    Remove-Item Env:OPENSSL_CONF
}

function Invoke-OpenSsl {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )
    # OpenSSL writes progress to stderr. Capture both streams without turning a
    # successful native command into a PowerShell terminating error.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $openssl @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) {
        $output | ForEach-Object { Write-Output $_ }
        throw $FailureMessage
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path -LiteralPath $OutDir).Path

$caKey = Join-Path $OutDir 'ca.key'
$caCert = Join-Path $OutDir 'ca.crt'
$serverKey = Join-Path $OutDir 'server.key'
$serverCert = Join-Path $OutDir 'server.crt'
$csr = Join-Path $OutDir 'server.csr'
$ext = Join-Path $OutDir 'server.ext'

@'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1
'@ | Set-Content -LiteralPath $ext -Encoding ascii

# Local test CA. Keep ca.key private; delete it with the directory afterwards.
Invoke-OpenSsl -FailureMessage 'Failed to create the local test CA.' -Arguments @(
    'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', "$Days", '-nodes',
    '-keyout', $caKey, '-out', $caCert, '-subj', '/CN=CPDTS Local Test CA',
    '-addext', 'basicConstraints=critical,CA:TRUE',
    '-addext', 'keyUsage=critical,keyCertSign,cRLSign'
)

Invoke-OpenSsl -FailureMessage 'Failed to create the server key and request.' -Arguments @(
    'req', '-newkey', 'rsa:2048', '-sha256', '-nodes',
    '-keyout', $serverKey, '-out', $csr, '-subj', '/CN=localhost'
)

Invoke-OpenSsl -FailureMessage 'Failed to sign the server certificate.' -Arguments @(
    'x509', '-req', '-in', $csr, '-CA', $caCert, '-CAkey', $caKey, '-CAcreateserial',
    '-out', $serverCert, '-days', "$Days", '-sha256', '-extfile', $ext
)

Remove-Item -LiteralPath $csr, $ext -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $OutDir 'server.srl') -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $OutDir 'ca.srl') -ErrorAction SilentlyContinue

Write-Output "CA certificate:     $caCert"
Write-Output "Server certificate: $serverCert"
Write-Output "Server private key: $serverKey (keep private; never commit)"
Write-Output ''
Write-Output 'Automated tests verify this chain and use ignoreHTTPSErrors for the local origin.'
Write-Output 'To browse with normal certificate validation, import ONLY ca.crt into the current-user Trusted Root store:'
Write-Output "  Import-Certificate -FilePath '$caCert' -CertStoreLocation 'Cert:\CurrentUser\Root'"
Write-Output 'Then restart the browser. Remove the CA when it is no longer needed:'
Write-Output '  Get-ChildItem Cert:\CurrentUser\Root | Where-Object Subject -like "*CPDTS Local Test CA*" | Remove-Item'
