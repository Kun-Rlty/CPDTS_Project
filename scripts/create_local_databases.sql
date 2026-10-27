-- scripts/create_local_databases.sql
--
-- Ensures the DISPOSABLE TEST database (cpdts_test) exists for CPDTS and
-- reports the state of the relevant local databases.
--
-- DATABASE POLICY ENFORCED BY THIS SCRIPT:
--   * cpdts_dev  = local DEVELOPMENT database, created manually by the project
--                  owner. This script NEVER creates, modifies or drops it;
--                  it only reports whether it exists.
--   * cpdts_test = disposable TEST database. Created here ONLY if missing.
--   * cpdts      = legacy name from earlier instructions. NEVER created,
--                  modified or dropped here. If it exists, it is only listed.
--   * No production database is ever touched. Nothing is ever dropped or reset.
--
-- Encoding: UTF-8 (taken from template1 when possible; otherwise created from
-- template0 with UTF8 + C collation).
--
-- HOW TO RUN (in YOUR OWN terminal; psql asks for your password privately -
-- it is never shown, logged or shared with anyone):
--
--   "C:\Program Files\PostgreSQL\18\bin\psql.exe" -h localhost -p 5432 -U postgres -d postgres -f scripts\create_local_databases.sql
--
-- (Replace "postgres" with your own superuser role name if different.)

\set ON_ERROR_STOP on

\echo '--- Step 1: current state of the relevant local databases (read-only) ---'
SELECT datname, pg_encoding_to_char(encoding) AS encoding
FROM pg_database
WHERE datname IN ('cpdts_dev', 'cpdts_test', 'cpdts')
ORDER BY datname;

\echo '--- Step 2: cpdts_dev - REPORT ONLY (never created, modified or dropped here) ---'
SELECT CASE
    WHEN EXISTS (SELECT 1 FROM pg_database WHERE datname = 'cpdts_dev')
        THEN 'SELECT ''cpdts_dev exists - development database, left untouched by this script'''
    ELSE 'SELECT ''WARNING: cpdts_dev does NOT exist - the project owner must create it; this script will not'''
END AS ddl \gexec

\echo '--- Step 3: cpdts_test - create ONLY if it does not exist ---'
SELECT CASE
    WHEN EXISTS (SELECT 1 FROM pg_database WHERE datname = 'cpdts_test')
        THEN 'SELECT ''cpdts_test already exists - left untouched'''
    WHEN (SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = 'template1') = 'UTF8'
        THEN 'CREATE DATABASE cpdts_test'
    ELSE 'CREATE DATABASE cpdts_test TEMPLATE template0 ENCODING ''UTF8'' LC_COLLATE ''C'' LC_CTYPE ''C'''
END AS ddl \gexec

\echo '--- Step 4: result (read-only) ---'
SELECT datname, pg_encoding_to_char(encoding) AS encoding
FROM pg_database
WHERE datname IN ('cpdts_dev', 'cpdts_test', 'cpdts')
ORDER BY datname;

\echo 'Done. cpdts_dev = development (owner-managed); cpdts_test = disposable test database.'
