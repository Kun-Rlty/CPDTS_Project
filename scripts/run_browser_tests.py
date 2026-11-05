"""Run the guarded browser suite against a disposable least-privilege database.

The runner creates a temporary PostgreSQL 18 cluster under the local temp
directory, creates separate one-time admin, migration-owner and restricted
runtime login roles, migrates and validates Flyway V1-V9 with the owner, applies
scripts/grant_runtime.sql, runs the SQL denial checks, seeds fixtures with the
owner, and starts the application with the runtime role only.

It never reads owner credentials from .env.backend, never touches cpdts_dev or
the retained cpdts_test database, and removes the disposable cluster at the end.
Generated passwords are process-local and are never printed.

After the HTTP development-profile browser suite, the runner builds the SPA,
starts a second backend with the proxy profile, starts the local HTTPS reverse
proxy, and verifies same-origin routing, secure cookies, CSRF, deep links and
the forwarded-header boundary. The HTTPS certificate and keys are generated
under the disposable run directory and removed afterwards.
"""
import os
import pathlib
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

import bcrypt

ROOT = pathlib.Path(__file__).resolve().parent.parent
FRONTEND = ROOT / 'frontend'
PG_BIN = pathlib.Path(os.environ.get('CPDTS_PG_BIN', r'C:\Program Files\PostgreSQL\18\bin'))
PG_PORT = 55432
BACKEND_PORT = 18081
HTTPS_BACKEND_PORT = 18082
FRONTEND_PORT = 15173
HTTPS_PORT = 18443
SECRETS = []


def redact(text):
    for secret in SECRETS:
        if secret:
            text = text.replace(secret, '<redacted>')
    return text


def free_port(port):
    with socket.socket() as sock:
        if os.name == 'nt':
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.bind(('127.0.0.1', port))


def run(args, *, env=None, input_text=None, timeout=300, log_path=None, cwd=ROOT):
    """Run a command; never include generated passwords in raised errors."""
    if log_path is not None:
        with open(log_path, 'a', encoding='utf-8') as log:
            result = subprocess.run(args, cwd=cwd, env=env, input=input_text, text=True,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        output = pathlib.Path(log_path).read_text(encoding='utf-8', errors='replace')
    else:
        result = subprocess.run(args, cwd=cwd, env=env, input=input_text, text=True,
                                capture_output=True, timeout=timeout)
        output = (result.stdout or '') + (result.stderr or '')
    if result.returncode != 0:
        raise RuntimeError(f'{pathlib.Path(args[0]).name} failed:\n{redact(output[-8000:])}')
    return output


def psql(psql_path, *, port, user, database, password, sql=None, script=None, variables=()):
    env = os.environ.copy()
    env.update(PGPASSWORD=password, PGCONNECT_TIMEOUT='10')
    args = [psql_path, '-h', '127.0.0.1', '-p', str(port), '-U', user, '-d', database,
            '-w', '-q', '-v', 'ON_ERROR_STOP=1']
    for variable in variables:
        args.extend(['-v', variable])
    if script is not None:
        args.extend(['-f', str(script)])
    return run(args, env=env, input_text=sql, timeout=180)


def wait(url, nonce, process, context=None):
    for _ in range(240):
        if process.poll() is not None:
            raise RuntimeError('A test server exited before readiness; inspect the retained logs.')
        try:
            with urllib.request.urlopen(url, timeout=1, context=context) as response:
                if response.headers.get('X-CPDTS-Browser-Test') == nonce:
                    return
        except (OSError, ValueError, ssl.SSLError):
            pass
        time.sleep(1)
    raise RuntimeError('Timed out waiting for the guarded test server.')


def assert_loopback_listener(port, label):
    """Fail if the server listens on anything except loopback."""
    command = ("Get-NetTCPConnection -State Listen -LocalPort %d -ErrorAction Stop "
               "| Select-Object -ExpandProperty LocalAddress" % port)
    try:
        result = subprocess.run(['powershell', '-NoProfile', '-Command', command],
                                text=True, capture_output=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(f'Could not inspect the {label} listener: {error}') from error
    if result.returncode != 0:
        raise RuntimeError(f'No listening {label} socket was found on port {port}.')
    addresses = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not addresses:
        raise RuntimeError(f'No listening {label} socket was found on port {port}.')
    unsafe = [address for address in addresses if address not in ('127.0.0.1', '::1')]
    if unsafe:
        raise RuntimeError(f'{label} is listening on non-loopback address(es): {", ".join(unsafe)}')


def stop_cluster(data_dir, log_path):
    if not data_dir.exists():
        return
    pg_ctl = PG_BIN / 'pg_ctl.exe'
    try:
        with open(log_path, 'a', encoding='utf-8') as log:
            subprocess.run([str(pg_ctl), '-D', str(data_dir), '-m', 'fast', '-w', 'stop'],
                           text=True, stdout=log, stderr=subprocess.STDOUT, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        pass
    pidfile = data_dir / 'postmaster.pid'
    if pidfile.exists() and os.name == 'nt':
        try:
            pid = pidfile.read_text(encoding='utf-8').splitlines()[0].strip()
            subprocess.run(['taskkill', '/PID', pid, '/T', '/F'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        except (OSError, IndexError, ValueError):
            pass


def run_https_suite(*, npm, mvn, backend_env, ui_env, nonce, logs, tls_dir,
                    processes, open_files):
    """Build the SPA, start a proxy-profile backend and HTTPS proxy, run HTTPS tests."""
    run([npm, 'run', 'build'], cwd=FRONTEND, env=ui_env,
        log_path=logs / 'frontend-build.log', timeout=600)

    powershell = shutil.which('powershell') or shutil.which('powershell.exe')
    openssl = shutil.which('openssl')
    node = shutil.which('node')
    if not powershell or not openssl or not node:
        raise RuntimeError('PowerShell, OpenSSL and Node are required for the HTTPS checks.')
    run([powershell, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
         str(ROOT / 'scripts/create_local_https_cert.ps1'),
         '-OutDir', str(tls_dir), '-Days', '2'],
        log_path=logs / 'tls-generate.log', timeout=180)
    run([openssl, 'verify', '-CAfile', str(tls_dir / 'ca.crt'), str(tls_dir / 'server.crt')],
        log_path=logs / 'tls-verify.log', timeout=60)

    proxy_token = secrets.token_urlsafe(32)
    SECRETS.append(proxy_token)

    # A second application instance uses the same disposable database, but the
    # deployment profile: loopback-only listener, secure cookie and trusted
    # proxy boundary. The HTTP development-profile instance stays untouched.
    https_env = backend_env.copy()
    https_env.update(SPRING_PROFILES_ACTIVE='proxy', CPDTS_PROXY_TOKEN=proxy_token,
                     CPDTS_BACKEND_PORT=str(HTTPS_BACKEND_PORT))
    https_backend_log = open(logs / 'https-backend.log', 'w', encoding='utf-8')
    open_files.append(https_backend_log)
    https_backend = subprocess.Popen(
        [mvn, '-B', '-f', str(ROOT / 'backend/pom.xml'), 'spring-boot:test-run',
         '-Dspring-boot.run.main-class=com.cpdts.support.BrowserTestServer'],
        cwd=ROOT, env=https_env, stdout=https_backend_log, stderr=subprocess.STDOUT)
    processes.append(https_backend)
    wait(f'http://127.0.0.1:{HTTPS_BACKEND_PORT}/api/v1/auth/csrf', nonce, https_backend)
    assert_loopback_listener(BACKEND_PORT, 'HTTP test backend')
    assert_loopback_listener(HTTPS_BACKEND_PORT, 'HTTPS test backend')

    proxy_env = os.environ.copy()
    for key in ('CPDTS_DB_URL', 'CPDTS_DB_USER', 'CPDTS_DB_PASSWORD',
                'CPDTS_MIGRATION_USER', 'CPDTS_MIGRATION_PASSWORD', 'PGPASSWORD',
                'CPDTS_E2E_A', 'CPDTS_E2E_B', 'CPDTS_E2E_PLATFORM',
                'CPDTS_E2E_CREATED', 'CPDTS_E2E_PASSWORD', 'CPDTS_E2E_NONCE'):
        proxy_env.pop(key, None)
    proxy_env.update(CPDTS_HTTPS_PORT=str(HTTPS_PORT),
                     CPDTS_UPSTREAM_PORT=str(HTTPS_BACKEND_PORT),
                     CPDTS_DIST=str(FRONTEND / 'dist'),
                     CPDTS_TLS_CERT=str(tls_dir / 'server.crt'),
                     CPDTS_TLS_KEY=str(tls_dir / 'server.key'),
                     CPDTS_PROXY_TOKEN=proxy_token)
    proxy_log = open(logs / 'https-proxy.log', 'w', encoding='utf-8')
    open_files.append(proxy_log)
    proxy = subprocess.Popen([node, str(ROOT / 'scripts/https_proxy.mjs')], cwd=ROOT,
                             env=proxy_env, stdout=proxy_log, stderr=subprocess.STDOUT)
    processes.append(proxy)
    wait(f'https://localhost:{HTTPS_PORT}/api/v1/auth/csrf', nonce, proxy,
         context=ssl._create_unverified_context())
    assert_loopback_listener(HTTPS_PORT, 'HTTPS test proxy')

    https_ui_env = ui_env.copy()
    https_ui_env.update(CPDTS_HTTPS_BACKEND_PORT=str(HTTPS_BACKEND_PORT),
                        CPDTS_HTTPS_PORT=str(HTTPS_PORT))
    result = subprocess.run(
        [npm, 'exec', '--', 'playwright', 'test', '--config=playwright.https.config.ts'],
        cwd=FRONTEND, env=https_ui_env, text=True, capture_output=True, timeout=900)
    output = (result.stdout or '') + (result.stderr or '')
    (logs / 'playwright-https.log').write_text(redact(output), encoding='utf-8')
    if result.returncode != 0:
        raise RuntimeError('HTTPS Playwright suite failed:\n' + redact(output[-12000:]))
    print(redact(output), flush=True)
    print('HTTPS proxy checks: production build, secure cookie, SPA routing, CSRF, '
          'forwarded-header boundary and loopback-only listeners passed.', flush=True)


def main():
    if sys.argv[1:]:
        raise RuntimeError('This runner accepts no database, server or port overrides.')
    for name in ('initdb.exe', 'pg_ctl.exe', 'psql.exe'):
        if not (PG_BIN / name).is_file():
            raise RuntimeError(f'PostgreSQL binary not found: {PG_BIN / name}')
    for port in (PG_PORT, BACKEND_PORT, HTTPS_BACKEND_PORT, FRONTEND_PORT, HTTPS_PORT):
        free_port(port)

    temp_root = pathlib.Path(tempfile.gettempdir()) / 'opencode'
    run_root = pathlib.Path(tempfile.mkdtemp(
        prefix='cpdts-lp-browser-', dir=temp_root if temp_root.is_dir() else None))
    logs = run_root / 'logs'
    logs.mkdir()
    data_dir = run_root / 'pgdata'
    tls_dir = run_root / 'tls'
    admin_password_file = run_root / 'admin.pw'
    print(f'Disposable test artifacts: {run_root}', flush=True)

    # Random names and process-local passwords; none are written to the repo.
    suffix = secrets.token_hex(6)
    database = f'cpdts_lp_{suffix}'
    owner_role = f'cpdts_lp_owner_{suffix}'
    runtime_role = f'cpdts_lp_runtime_{suffix}'
    admin_role = f'cpdts_lp_admin_{suffix}'
    admin_password = secrets.token_urlsafe(32)
    owner_password = secrets.token_urlsafe(32)
    runtime_password = secrets.token_urlsafe(32)
    SECRETS.extend([admin_password, owner_password, runtime_password])
    admin_password_file.write_text(admin_password, encoding='utf-8')

    psql_path = str(PG_BIN / 'psql.exe')
    pg_ctl = str(PG_BIN / 'pg_ctl.exe')
    mvn = shutil.which('mvn.cmd') or shutil.which('mvn')
    npm = shutil.which('npm.cmd') or shutil.which('npm')
    if not mvn or not npm:
        raise RuntimeError('Maven and npm are required.')

    processes = []
    open_files = []
    backend_env = None
    try:
        # 1. A fresh cluster, isolated from the owner's existing databases and roles.
        run([str(PG_BIN / 'initdb.exe'), '-D', str(data_dir), '-U', admin_role,
             f'--pwfile={admin_password_file}', '--auth=scram-sha-256', '--encoding=UTF8',
             '--locale=C', '--no-sync'], log_path=logs / 'initdb.log', timeout=180)
        run([pg_ctl, '-D', str(data_dir), '-l', str(logs / 'postgres.log'),
             '-o', f'-p {PG_PORT} -h 127.0.0.1', '-w', 'start'],
            log_path=logs / 'pg_ctl-start.log', timeout=90)

        # 2. Separate owner/migration and restricted runtime roles; no owner membership.
        provision = f"""
CREATE ROLE {owner_role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD '{owner_password}';
CREATE ROLE {runtime_role} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT PASSWORD '{runtime_password}';
CREATE DATABASE {database} OWNER {owner_role} TEMPLATE template0;
REVOKE TEMPORARY ON DATABASE {database} FROM PUBLIC;
"""
        psql(psql_path, port=PG_PORT, user=admin_role, database='postgres',
             password=admin_password, sql=provision)
        admin_password_file.unlink()

        url = f'jdbc:postgresql://127.0.0.1:{PG_PORT}/{database}'

        # 3. Migrate and validate V1-V9 with the owner, never with runtime.
        migration_env = os.environ.copy()
        for key in ('CPDTS_DB_USER', 'CPDTS_DB_PASSWORD', 'SPRING_APPLICATION_JSON'):
            migration_env.pop(key, None)
        migration_env.update(CPDTS_DB_URL=url, CPDTS_MIGRATION_USER=owner_role,
                             CPDTS_MIGRATION_PASSWORD=owner_password)
        run([mvn, '-B', '-f', str(ROOT / 'backend/pom.xml'), 'flyway:migrate'],
            env=migration_env, log_path=logs / 'flyway-migrate.log', timeout=600)
        run([mvn, '-B', '-f', str(ROOT / 'backend/pom.xml'), 'flyway:validate'],
            env=migration_env, log_path=logs / 'flyway-validate.log', timeout=600)

        # 4. Apply the existing runtime grant profile as the object-owning role.
        psql(psql_path, port=PG_PORT, user=owner_role, database=database,
             password=owner_password, script=ROOT / 'scripts/grant_runtime.sql',
             variables=(f'runtime_role={runtime_role}',))

        # 5. Database-level least-privilege checks, then direct runtime denial checks.
        run([psql_path, '-h', '127.0.0.1', '-p', str(PG_PORT), '-U', admin_role,
             '-d', database, '-w', '-q', '-v', 'ON_ERROR_STOP=1',
             '-v', f'runtime_role={runtime_role}',
             '-f', str(ROOT / 'tests/database/least_privilege_checks.sql')],
            env={**os.environ, 'PGPASSWORD': admin_password, 'PGCONNECT_TIMEOUT': '10'},
            log_path=logs / 'least-privilege.log', timeout=180)
        run([psql_path, '-h', '127.0.0.1', '-p', str(PG_PORT), '-U', runtime_role,
             '-d', database, '-w', '-q', '-v', 'ON_ERROR_STOP=1',
             '-f', str(ROOT / 'tests/database/runtime_denial_checks.sql')],
            env={**os.environ, 'PGPASSWORD': runtime_password, 'PGCONNECT_TIMEOUT': '10'},
            log_path=logs / 'runtime-denial.log', timeout=180)

        # 6. Fixtures use the owner only for setup; the application uses runtime.
        run_id = uuid.uuid4().hex
        fixtures = {
            'CPDTS_E2E_A': f'browser-a-{run_id}@example.test',
            'CPDTS_E2E_B': f'browser-b-{run_id}@example.test',
            'CPDTS_E2E_PLATFORM': f'browser-platform-{run_id}@example.test',
            'CPDTS_E2E_CREATED': f'browser-created-{run_id}@example.test',
        }
        fixture_password = secrets.token_urlsafe(24)
        SECRETS.append(fixture_password)
        hashed = bcrypt.hashpw(fixture_password.encode(), bcrypt.gensalt(rounds=10)).decode()
        seed = f"""
BEGIN;
DO $$ BEGIN IF pg_catalog.current_database() <> '{database}' THEN RAISE EXCEPTION 'unsafe database'; END IF; END $$;
INSERT INTO users(email,password_hash,full_name,status,is_platform_admin)
VALUES ('{fixtures['CPDTS_E2E_A']}','{hashed}','Browser Test A','ACTIVE',false),
       ('{fixtures['CPDTS_E2E_B']}','{hashed}','Browser Test B','ACTIVE',false),
       ('{fixtures['CPDTS_E2E_PLATFORM']}','{hashed}','Browser Test Platform','ACTIVE',true);
INSERT INTO company_memberships(company_id,user_id,role_id,status)
SELECT c.id,u.id,r.id,'ACTIVE' FROM companies c, users u, roles r
WHERE (u.email='{fixtures['CPDTS_E2E_A']}' AND c.code='JMX' AND r.code='FINANCE_OFFICER')
   OR (u.email='{fixtures['CPDTS_E2E_A']}' AND c.code='KAM' AND r.code='VIEWER')
   OR (u.email='{fixtures['CPDTS_E2E_B']}' AND c.code='JMX' AND r.code='COMPANY_ADMIN');
COMMIT;
"""
        psql(psql_path, port=PG_PORT, user=owner_role, database=database,
             password=owner_password, sql=seed)

        nonce = secrets.token_hex(32)
        backend_env = os.environ.copy()
        for key in ('CPDTS_MIGRATION_USER', 'CPDTS_MIGRATION_PASSWORD', 'PGPASSWORD',
                    'SPRING_APPLICATION_JSON', 'SPRING_DATASOURCE_URL',
                    'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD',
                    'SPRING_PROFILES_ACTIVE'):
            backend_env.pop(key, None)
        backend_env.update(CPDTS_DB_URL=url, CPDTS_DB_USER=runtime_role,
                           CPDTS_DB_PASSWORD=runtime_password, CPDTS_E2E_NONCE=nonce)

        ui_env = os.environ.copy()
        for key in ('CPDTS_MIGRATION_USER', 'CPDTS_MIGRATION_PASSWORD', 'PGPASSWORD',
                    'CPDTS_DB_USER', 'CPDTS_DB_PASSWORD', 'SPRING_APPLICATION_JSON',
                    'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
                    'SPRING_DATASOURCE_PASSWORD', 'SPRING_PROFILES_ACTIVE'):
            ui_env.pop(key, None)
        ui_env.update(fixtures, CPDTS_E2E_PASSWORD=fixture_password, CPDTS_E2E_NONCE=nonce)

        backend_log = open(logs / 'backend.log', 'w', encoding='utf-8')
        open_files.append(backend_log)
        backend = subprocess.Popen(
            [mvn, '-B', '-f', str(ROOT / 'backend/pom.xml'), 'spring-boot:test-run',
             '-Dspring-boot.run.main-class=com.cpdts.support.BrowserTestServer'],
            cwd=ROOT, env=backend_env, stdout=backend_log, stderr=subprocess.STDOUT)
        processes.append(backend)
        wait(f'http://localhost:{BACKEND_PORT}/api/v1/auth/csrf', nonce, backend)

        frontend_log = open(logs / 'frontend.log', 'w', encoding='utf-8')
        open_files.append(frontend_log)
        frontend = subprocess.Popen(
            [npm, 'run', 'dev', '--', '--config', 'vite.e2e.config.ts'],
            cwd=FRONTEND, env=ui_env, stdout=frontend_log, stderr=subprocess.STDOUT)
        processes.append(frontend)
        wait(f'http://127.0.0.1:{FRONTEND_PORT}/api/v1/auth/csrf', nonce, frontend)

        result = subprocess.run([npm, 'exec', 'playwright', 'test'], cwd=FRONTEND,
                                env=ui_env, text=True, capture_output=True, timeout=900)
        playwright_output = (result.stdout or '') + (result.stderr or '')
        (logs / 'playwright.log').write_text(redact(playwright_output), encoding='utf-8')
        if result.returncode != 0:
            raise RuntimeError('Playwright failed:\n' + redact(playwright_output[-12000:]))

        print(redact(playwright_output), flush=True)
        run_https_suite(npm=npm, mvn=mvn, backend_env=backend_env, ui_env=ui_env,
                        nonce=nonce, logs=logs, tls_dir=tls_dir,
                        processes=processes, open_files=open_files)
        print(f'Disposable database: {database} (removed after the run)', flush=True)
        print(f'Migration owner role: {owner_role}; application runtime role: {runtime_role}', flush=True)
        print('Database privilege checks: least privilege and direct runtime denial passed.', flush=True)
    finally:
        # Preserve audit history while the disposable database still exists.
        if backend_env is not None and (data_dir / 'postmaster.pid').exists():
            try:
                psql(psql_path, port=PG_PORT, user=owner_role, database=database,
                     password=owner_password, sql=f"""
BEGIN;
DO $$ BEGIN IF pg_catalog.current_database() <> '{database}' THEN RAISE EXCEPTION 'unsafe database'; END IF; END $$;
UPDATE users SET status='DISABLED', is_platform_admin=false
WHERE email IN ('{fixtures['CPDTS_E2E_A']}', '{fixtures['CPDTS_E2E_B']}',
                '{fixtures['CPDTS_E2E_PLATFORM']}', '{fixtures['CPDTS_E2E_CREATED']}');
COMMIT;
""")
            except (RuntimeError, OSError):
                pass
        for process in reversed(processes):
            if process.poll() is None:
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   check=False)
                else:
                    process.terminate()
        stop_cluster(data_dir, logs / 'pg_ctl-stop.log')
        for handle in open_files:
            handle.close()
        # Remove the cluster data, local certificate keys and password material;
        # keep logs only.
        admin_password_file.unlink(missing_ok=True)
        shutil.rmtree(data_dir, ignore_errors=True)
        shutil.rmtree(tls_dir, ignore_errors=True)
        print(f'Retained test logs: {logs}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'Browser test runner stopped: {redact(str(error))}', file=sys.stderr)
        sys.exit(1)
