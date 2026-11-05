-- Direct runtime-role denial checks for a disposable least-privilege database.
-- Connect as the RESTRICTED runtime login (not the owner and not a superuser).
-- The script refuses any database that is not a cpdts_lp_<12 hex> disposable DB
-- and refuses to run if the current role can create objects or is privileged.
\set ON_ERROR_STOP on
DO $$
BEGIN
  IF current_database() !~ '^cpdts_lp_[0-9a-f]{12}$' THEN RAISE EXCEPTION 'unsafe database'; END IF;
  IF (SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls OR rolinherit
        FROM pg_roles WHERE rolname = current_user) THEN
    RAISE EXCEPTION 'run this check as the restricted runtime role';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_auth_members
             WHERE member = (SELECT oid FROM pg_roles WHERE rolname = current_user)) THEN
    RAISE EXCEPTION 'runtime role must have no role memberships';
  END IF;
  IF pg_has_role(current_user, 'pg_database_owner', 'MEMBER') THEN
    RAISE EXCEPTION 'runtime role can assume the database owner';
  END IF;
  IF has_database_privilege(current_user, current_database(), 'CREATE')
     OR has_database_privilege(current_user, current_database(), 'TEMPORARY')
     OR has_schema_privilege(current_user, 'public', 'CREATE') THEN
    RAISE EXCEPTION 'runtime role has unsafe database or schema capabilities';
  END IF;
  IF has_column_privilege(current_user, 'public.users', 'is_platform_admin', 'INSERT')
     OR has_column_privilege(current_user, 'public.users', 'is_platform_admin', 'UPDATE') THEN
    RAISE EXCEPTION 'runtime role can write is_platform_admin';
  END IF;
  IF has_table_privilege(current_user, 'public.audit_logs', 'UPDATE')
     OR has_table_privilege(current_user, 'public.audit_logs', 'DELETE')
     OR has_table_privilege(current_user, 'public.payment_verifications', 'UPDATE')
     OR has_table_privilege(current_user, 'public.payment_verifications', 'DELETE') THEN
    RAISE EXCEPTION 'runtime role can alter append-only history';
  END IF;
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relkind IN ('r','p')
               AND has_table_privilege(current_user, c.oid, 'TRUNCATE')) THEN
    RAISE EXCEPTION 'runtime role can TRUNCATE an application table';
  END IF;
END $$;

DO $$
BEGIN
  BEGIN
    UPDATE users SET is_platform_admin = true WHERE email LIKE 'browser-%@example.test';
    RAISE EXCEPTION 'platform flag update unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: UPDATE users.is_platform_admin'; END;
  BEGIN
    INSERT INTO users (email, password_hash, full_name, status, is_platform_admin)
    VALUES ('forbidden-platform@example.test', repeat('x', 60), 'Forbidden', 'ACTIVE', true);
    RAISE EXCEPTION 'platform flag insert unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: INSERT users.is_platform_admin'; END;
  BEGIN
    UPDATE audit_logs SET action = 'forbidden';
    RAISE EXCEPTION 'audit update unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: UPDATE audit_logs'; END;
  BEGIN
    DELETE FROM audit_logs;
    RAISE EXCEPTION 'audit delete unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: DELETE audit_logs'; END;
  BEGIN
    UPDATE payment_verifications SET decision = 'FORBIDDEN';
    RAISE EXCEPTION 'verification update unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: UPDATE payment_verifications'; END;
  BEGIN
    DELETE FROM payment_verifications;
    RAISE EXCEPTION 'verification delete unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: DELETE payment_verifications'; END;
  BEGIN
    UPDATE idempotency_keys SET request_hash = 'forbidden';
    RAISE EXCEPTION 'idempotency update unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: UPDATE idempotency_keys'; END;
  BEGIN
    DELETE FROM idempotency_keys;
    RAISE EXCEPTION 'idempotency delete unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: DELETE idempotency_keys'; END;
  BEGIN
    UPDATE invoice_items SET description = 'Forbidden';
    RAISE EXCEPTION 'invoice item update unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: UPDATE invoice_items'; END;
  BEGIN
    UPDATE customers SET company_id = company_id;
    RAISE EXCEPTION 'customer company reassignment unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: UPDATE customers.company_id'; END;
  BEGIN
    INSERT INTO companies (code, display_name, status)
    VALUES ('FORBIDDEN', 'Forbidden', 'ACTIVE');
    RAISE EXCEPTION 'company insert unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: INSERT companies'; END;
  BEGIN
    DELETE FROM users WHERE email LIKE 'browser-%@example.test';
    RAISE EXCEPTION 'user delete unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: DELETE users'; END;
  BEGIN
    EXECUTE 'CREATE TABLE public.cpdts_forbidden_ddl(id int)';
    RAISE EXCEPTION 'DDL unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: CREATE TABLE'; END;
  BEGIN
    EXECUTE 'CREATE TEMP TABLE cpdts_forbidden_temp(id int)';
    RAISE EXCEPTION 'temporary DDL unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: CREATE TEMP TABLE'; END;
  BEGIN
    EXECUTE 'ALTER TABLE public.customers ADD COLUMN cpdts_forbidden text';
    RAISE EXCEPTION 'ALTER TABLE unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: ALTER TABLE'; END;
  BEGIN
    EXECUTE 'DROP TABLE public.invoice_items';
    RAISE EXCEPTION 'DROP TABLE unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: DROP TABLE'; END;
  BEGIN
    EXECUTE 'CREATE ROLE cpdts_forbidden_role';
    RAISE EXCEPTION 'role creation unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: CREATE ROLE'; END;
  BEGIN
    EXECUTE 'TRUNCATE TABLE public.users';
    RAISE EXCEPTION 'users TRUNCATE unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: TRUNCATE users'; END;
  BEGIN
    EXECUTE 'TRUNCATE TABLE public.invoice_items';
    RAISE EXCEPTION 'invoice items TRUNCATE unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: TRUNCATE invoice_items'; END;
  BEGIN
    EXECUTE 'TRUNCATE TABLE public.audit_logs';
    RAISE EXCEPTION 'audit TRUNCATE unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: TRUNCATE audit_logs'; END;
  BEGIN
    EXECUTE 'TRUNCATE TABLE public.invoices';
    RAISE EXCEPTION 'invoices TRUNCATE unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: TRUNCATE invoices'; END;
  BEGIN
    EXECUTE 'TRUNCATE TABLE public.payments';
    RAISE EXCEPTION 'payments TRUNCATE unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN RAISE NOTICE 'denied: TRUNCATE payments'; END;
END $$;

SELECT 'runtime denial checks passed' AS result;
