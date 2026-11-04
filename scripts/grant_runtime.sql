-- Run as the object-owning migration administrator in the intended database.
-- Supply a dedicated existing LOGIN role with: psql -v runtime_role=<role> -f ...
-- This script does not create or modify roles or passwords.
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('cpdts.runtime_role', :'runtime_role', true);
DO $$
DECLARE r oid; role_name text := current_setting('cpdts.runtime_role');
BEGIN
    SELECT oid INTO r FROM pg_roles WHERE rolname = role_name AND rolcanlogin
        AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit;
    IF r IS NULL THEN RAISE EXCEPTION 'runtime must be an existing unprivileged NOINHERIT login role'; END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = r) THEN RAISE EXCEPTION 'runtime must have no role memberships'; END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relowner=r)
       OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proowner=r)
       OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspname='public' AND nspowner=r)
       OR EXISTS (SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba=r)
       THEN RAISE EXCEPTION 'runtime must not own the database, schema or objects'; END IF;
    IF has_database_privilege(r, current_database(), 'CREATE') OR EXISTS
        (SELECT 1 FROM pg_namespace n WHERE has_schema_privilege(r,n.oid,'CREATE'))
        THEN RAISE EXCEPTION 'runtime already has database or schema CREATE'; END IF;
    IF has_database_privilege(r, current_database(), 'TEMPORARY') THEN RAISE EXCEPTION 'runtime has TEMPORARY via a direct or PUBLIC database grant'; END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace,
        LATERAL aclexplode(c.relacl) a WHERE n.nspname='public' AND a.grantee IN (0,r) AND a.is_grantable)
       OR EXISTS (SELECT 1 FROM pg_attribute col JOIN pg_class c ON c.oid=col.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace,
        LATERAL aclexplode(col.attacl) a WHERE n.nspname='public' AND a.grantee IN (0,r) AND a.is_grantable)
       OR EXISTS (SELECT 1 FROM pg_namespace n, LATERAL aclexplode(n.nspacl) a WHERE n.nspname='public' AND a.grantee IN (0,r) AND a.is_grantable)
       THEN RAISE EXCEPTION 'runtime has an unsafe direct or PUBLIC grant option'; END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND c.relname IN ('companies','roles','permissions','role_permissions','flyway_schema_history')
        AND has_table_privilege(r,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'))
        THEN RAISE EXCEPTION 'runtime has unsafe privileges on reference tables or migration history'; END IF;
    IF EXISTS (SELECT 1 FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND a.attnum>0 AND NOT a.attisdropped
        AND c.relname IN ('companies','roles','permissions','role_permissions','flyway_schema_history','audit_logs','payment_verifications')
        AND (has_column_privilege(r,c.oid,a.attnum,'UPDATE') OR has_column_privilege(r,c.oid,a.attnum,'REFERENCES')
          OR (c.relname IN ('companies','roles','permissions','role_permissions','flyway_schema_history') AND has_column_privilege(r,c.oid,a.attnum,'INSERT'))))
        THEN RAISE EXCEPTION 'runtime has unsafe pre-existing column privileges'; END IF;
    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='public' AND p.prosecdef AND has_function_privilege(r,p.oid,'EXECUTE'))
        THEN RAISE EXCEPTION 'review executable SECURITY DEFINER functions before granting runtime'; END IF;
    IF has_column_privilege(r,'public.users','is_platform_admin','INSERT') OR has_column_privilege(r,'public.users','is_platform_admin','UPDATE')
       OR has_table_privilege(r,'public.customers','UPDATE') OR has_table_privilege(r,'public.invoices','UPDATE')
       OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p')
          AND (has_table_privilege(r,c.oid,'TRUNCATE') OR has_table_privilege(r,c.oid,'TRIGGER') OR has_table_privilege(r,c.oid,'REFERENCES')
               OR (c.relname <> 'invoice_items' AND has_table_privilege(r,c.oid,'DELETE'))))
       OR has_table_privilege(r,'public.audit_logs','UPDATE,DELETE')
       OR has_table_privilege(r,'public.payment_verifications','UPDATE,DELETE')
       OR has_table_privilege(r,'public.idempotency_keys','UPDATE,DELETE')
       THEN RAISE EXCEPTION 'runtime has unsafe pre-existing direct or PUBLIC privileges'; END IF;
END $$;
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'runtime_role') \gexec
SELECT format('GRANT SELECT ON public.flyway_schema_history, public.companies, public.roles, public.permissions, public.role_permissions, public.users, public.company_memberships, public.customers, public.invoices, public.invoice_items, public.payments, public.payment_verifications, public.payment_allocations, public.number_sequences, public.audit_logs, public.idempotency_keys TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT (email, password_hash, full_name, status), UPDATE (last_login_at, updated_at) ON public.users TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT, UPDATE (role_id, status, updated_at) ON public.company_memberships TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT, UPDATE (display_name, phone, email, address, notes, status, updated_at, updated_by) ON public.customers TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT, UPDATE (issue_date, due_date, description, total_amount, status, issued_at, voided_at, void_reason, updated_at, updated_by) ON public.invoices TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT, DELETE ON public.invoice_items TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT, UPDATE (status, updated_at, reversed_at, reversed_by, reversal_reason) ON public.payments TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT ON public.payment_verifications TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT, UPDATE (status, reversed_at, reversed_by, reversal_reason) ON public.payment_allocations TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT, UPDATE (next_value, updated_at) ON public.number_sequences TO %I', :'runtime_role') \gexec
SELECT format('GRANT INSERT ON public.audit_logs, public.idempotency_keys TO %I', :'runtime_role') \gexec
COMMIT;
