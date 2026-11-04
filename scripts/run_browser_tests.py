"""Run isolated browser tests against local cpdts_test; never reset data.

Requires Python bcrypt, PostgreSQL psql, Java/Maven, Node and Microsoft Edge.
Fixture users and financial records use unique test values and are retained to
preserve audit history. Credentials are random, process-local and never logged.
"""
import os
import pathlib
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

import bcrypt

ROOT = pathlib.Path(__file__).resolve().parent.parent
FRONTEND = ROOT / 'frontend'
JDBC = 'jdbc:postgresql://localhost:5432/cpdts_test'


def free_port(port):
    with socket.socket() as sock:
        if os.name == 'nt':
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        sock.bind(('127.0.0.1', port))


def wait(url, nonce, process):
    for _ in range(180):
        if process.poll() is not None:
            raise RuntimeError('A test server exited before readiness; inspect the temporary server logs.')
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                if response.headers.get('X-CPDTS-Browser-Test') == nonce:
                    return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('Timed out waiting for the guarded test server.')


def main():
    if sys.argv[1:]:
        raise RuntimeError('This runner accepts no database, server or port overrides.')
    for port in (18081, 15173):
        free_port(port)  # Never reuse an existing, potentially non-test server.
    config = {}
    for line in (ROOT / '.env.backend').read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.strip().startswith('#'):
            key, value = line.split('=', 1)
            config[key.strip()] = value.strip()
    if config.get('DB_TEST_NAME') != 'cpdts_test':
        raise RuntimeError('DB_TEST_NAME must be exactly cpdts_test.')
    if not config.get('DB_USER') or not config.get('DB_PASSWORD'):
        raise RuntimeError('Local database credentials are missing.')
    env = os.environ.copy()
    env.update(CPDTS_DB_URL=JDBC, CPDTS_DB_USER=config['DB_USER'], CPDTS_DB_PASSWORD=config['DB_PASSWORD'],
               PGPASSWORD=config['DB_PASSWORD'], CPDTS_E2E_NONCE=secrets.token_hex(32))
    run_id = uuid.uuid4().hex
    env['CPDTS_E2E_A'] = f'browser-a-{run_id}@example.test'
    env['CPDTS_E2E_B'] = f'browser-b-{run_id}@example.test'
    env['CPDTS_E2E_PLATFORM'] = f'browser-platform-{run_id}@example.test'
    env['CPDTS_E2E_CREATED'] = f'browser-created-{run_id}@example.test'
    env['CPDTS_E2E_PASSWORD'] = secrets.token_urlsafe(24)
    hashed = bcrypt.hashpw(env['CPDTS_E2E_PASSWORD'].encode(), bcrypt.gensalt(rounds=10)).decode()
    psql = shutil.which('psql') or r'C:\Program Files\PostgreSQL\18\bin\psql.exe'
    # Guard inside the transaction before any insert; no deletion or truncate.
    sql = f"""
BEGIN;
DO $$ BEGIN IF pg_catalog.current_database() <> 'cpdts_test' THEN RAISE EXCEPTION 'unsafe database'; END IF; END $$;
INSERT INTO users(email,password_hash,full_name,status,is_platform_admin)
VALUES ('{env['CPDTS_E2E_A']}','{hashed}','Browser Test A','ACTIVE',false),
       ('{env['CPDTS_E2E_B']}','{hashed}','Browser Test B','ACTIVE',false),
       ('{env['CPDTS_E2E_PLATFORM']}','{hashed}','Browser Test Platform','ACTIVE',true);
INSERT INTO company_memberships(company_id,user_id,role_id,status)
SELECT c.id,u.id,r.id,'ACTIVE' FROM companies c, users u, roles r
WHERE (u.email='{env['CPDTS_E2E_A']}' AND c.code='JMX' AND r.code='FINANCE_OFFICER')
   OR (u.email='{env['CPDTS_E2E_A']}' AND c.code='KAM' AND r.code='VIEWER')
   OR (u.email='{env['CPDTS_E2E_B']}' AND c.code='JMX' AND r.code='COMPANY_ADMIN');
COMMIT;
"""
    subprocess.run([psql, '-h', 'localhost', '-p', '5432', '-U', config['DB_USER'], '-d', 'cpdts_test',
                    '-v', 'ON_ERROR_STOP=1', '-q'], input=sql, text=True, env=env, check=True)
    processes = []
    temp_root = pathlib.Path(tempfile.gettempdir()) / 'opencode'
    tmp = tempfile.mkdtemp(prefix='cpdts-browser-', dir=temp_root if temp_root.is_dir() else None)
    print(f'Test server logs: {tmp}', flush=True)
    try:
        logs = [open(pathlib.Path(tmp) / name, 'w', encoding='utf-8') for name in ('backend.log', 'frontend.log')]
        mvn = shutil.which('mvn.cmd') or shutil.which('mvn')
        npm = shutil.which('npm.cmd') or shutil.which('npm')
        if not mvn or not npm:
            raise RuntimeError('Maven and npm are required.')
        backend = subprocess.Popen([mvn, '-B', '-f', str(ROOT / 'backend/pom.xml'), 'spring-boot:test-run',
                '-Dspring-boot.run.main-class=com.cpdts.support.BrowserTestServer'], cwd=ROOT, env=env, stdout=logs[0], stderr=subprocess.STDOUT)
        processes.append(backend)
        wait('http://localhost:18081/api/v1/auth/csrf', env['CPDTS_E2E_NONCE'], backend)
        frontend = subprocess.Popen([npm, 'run', 'dev', '--', '--config', 'vite.e2e.config.ts'], cwd=FRONTEND, env=env, stdout=logs[1], stderr=subprocess.STDOUT)
        processes.append(frontend)
        wait('http://127.0.0.1:15173/api/v1/auth/csrf', env['CPDTS_E2E_NONCE'], frontend)
        subprocess.run([npm, 'exec', 'playwright', 'test'], cwd=FRONTEND, env=env, check=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
                else:
                    process.terminate()
        for log in locals().get('logs', []):
            log.close()
        deactivate = f"""
BEGIN;
DO $$ BEGIN IF pg_catalog.current_database() <> 'cpdts_test' THEN RAISE EXCEPTION 'unsafe database'; END IF; END $$;
UPDATE users SET status='DISABLED', is_platform_admin=false
WHERE email IN ('{env['CPDTS_E2E_A']}', '{env['CPDTS_E2E_B']}', '{env['CPDTS_E2E_PLATFORM']}', '{env['CPDTS_E2E_CREATED']}');
COMMIT;
"""
        subprocess.run([psql, '-h', 'localhost', '-p', '5432', '-U', config['DB_USER'], '-d', 'cpdts_test',
                        '-v', 'ON_ERROR_STOP=1', '-q'], input=deactivate, text=True, env=env, check=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'Browser test runner stopped: {error}', file=sys.stderr)
        sys.exit(1)
