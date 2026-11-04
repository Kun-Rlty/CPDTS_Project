-- Disposable verification only. Requires an administrator connection and
-- -v runtime_role=<dedicated-test-role>. No retained database is accepted.
\set ON_ERROR_STOP on
DO $$ BEGIN
  IF current_database() !~ '^cpdts_lp_[0-9a-f]{12}$' THEN RAISE EXCEPTION 'unsafe database'; END IF;
END $$;
INSERT INTO customers(company_id,display_name) SELECT id,'Privilege fixture' FROM companies WHERE code='JMX';
INSERT INTO invoices(company_id,customer_id,invoice_number,issue_date,due_date,status,total_amount)
SELECT company_id,id,'LP-DRAFT',DATE '2026-01-01',DATE '2026-12-01','DRAFT',100 FROM customers WHERE display_name='Privilege fixture';
INSERT INTO invoice_items(company_id,invoice_id,description,quantity,unit_price)
SELECT company_id,id,'Draft item',1,100 FROM invoices WHERE invoice_number='LP-DRAFT';
INSERT INTO invoices(company_id,customer_id,invoice_number,issue_date,due_date,status,total_amount)
SELECT company_id,id,'LP-OTHER',DATE '2026-01-01',DATE '2026-12-01','DRAFT',100 FROM customers WHERE display_name='Privilege fixture';
SELECT format('SET ROLE %I', :'runtime_role') \gexec
DO $$
DECLARE inv bigint; co bigint; item bigint;
BEGIN
  IF has_table_privilege(current_user, 'public.users', 'SELECT WITH GRANT OPTION') THEN RAISE EXCEPTION 'runtime has grant option'; END IF;
  SELECT id,company_id INTO inv,co FROM invoices WHERE invoice_number='LP-DRAFT';
  INSERT INTO users(email,password_hash,full_name,status) VALUES ('least-privilege@example.test',repeat('x',60),'Test','ACTIVE');
  IF (SELECT is_platform_admin FROM users WHERE email='least-privilege@example.test') THEN RAISE EXCEPTION 'unsafe user default'; END IF;
  BEGIN
    INSERT INTO users(email,password_hash,full_name,status,is_platform_admin) VALUES ('escalation@example.test',repeat('x',60),'Test','ACTIVE',true);
    RAISE EXCEPTION 'platform flag insert unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE users SET is_platform_admin=true WHERE email='least-privilege@example.test';
    RAISE EXCEPTION 'platform flag update unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  DELETE FROM invoice_items WHERE invoice_id=inv;
  INSERT INTO invoice_items(company_id,invoice_id,description,quantity,unit_price) VALUES (co,inv,'Replacement',1,100);
  UPDATE invoices SET status='ISSUED',issued_at=now() WHERE id=inv;
  BEGIN
    UPDATE invoices SET status='DRAFT' WHERE id=inv;
    RAISE EXCEPTION 'issued invoice reverted to draft';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    DELETE FROM invoice_items WHERE invoice_id=inv;
    RAISE EXCEPTION 'issued item delete unexpectedly allowed';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    UPDATE invoice_items SET description='Forbidden' WHERE invoice_id=inv;
    RAISE EXCEPTION 'runtime item update unexpectedly allowed';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO invoice_items(company_id,invoice_id,description,quantity,unit_price) VALUES (co,inv,'Forbidden',1,1);
    RAISE EXCEPTION 'issued item insert unexpectedly allowed';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN EXECUTE 'CREATE TABLE public.forbidden_test(id int)'; RAISE EXCEPTION 'DDL unexpectedly allowed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN EXECUTE 'CREATE TEMP TABLE forbidden_temp(id int)'; RAISE EXCEPTION 'temporary DDL unexpectedly allowed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN EXECUTE 'TRUNCATE public.invoice_items'; RAISE EXCEPTION 'TRUNCATE unexpectedly allowed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN UPDATE audit_logs SET action='forbidden'; RAISE EXCEPTION 'audit update unexpectedly allowed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN DELETE FROM audit_logs; RAISE EXCEPTION 'audit delete unexpectedly allowed'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  SELECT id INTO item FROM invoice_items WHERE invoice_id=inv LIMIT 1;
  IF item IS NULL THEN RAISE EXCEPTION 'issued item missing'; END IF;
END $$;
RESET ROLE;
DO $$
DECLARE item bigint; other_item bigint; issued bigint;
BEGIN
  SELECT ii.id INTO item FROM invoice_items ii JOIN invoices i ON i.id=ii.invoice_id WHERE i.invoice_number='LP-DRAFT';
  BEGIN UPDATE invoice_items SET description='Forbidden' WHERE id=item; RAISE EXCEPTION 'issued item update unexpectedly allowed'; EXCEPTION WHEN check_violation THEN NULL; END;
  SELECT id INTO issued FROM invoices WHERE invoice_number='LP-DRAFT';
  INSERT INTO invoice_items(company_id,invoice_id,description,quantity,unit_price)
    SELECT company_id,id,'Other draft item',1,100 FROM invoices WHERE invoice_number='LP-OTHER' RETURNING id INTO other_item;
  BEGIN UPDATE invoice_items SET invoice_id=issued WHERE id=other_item; RAISE EXCEPTION 'reassignment to issued unexpectedly allowed'; EXCEPTION WHEN check_violation THEN NULL; END;
  UPDATE invoices SET status='VOIDED', voided_at=now(), void_reason='test' WHERE invoice_number='LP-OTHER';
  BEGIN UPDATE invoices SET status='DRAFT', voided_at=NULL, void_reason=NULL WHERE invoice_number='LP-OTHER'; RAISE EXCEPTION 'voided invoice reverted to draft'; EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN DELETE FROM invoice_items WHERE id=other_item; RAISE EXCEPTION 'voided item delete unexpectedly allowed'; EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
SELECT 'least privilege SQL checks passed' AS result;
