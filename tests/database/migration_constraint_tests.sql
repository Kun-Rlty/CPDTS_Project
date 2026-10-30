-- tests/database/migration_constraint_tests.sql
--
-- Database-level tests for the CPDTS Flyway migrations.
--
-- SAFETY:
--   * GUARDED: runs only against the disposable database "cpdts_test"
--     (the script refuses every other database, including cpdts_dev, cpdts and any
--     production database).
--   * Runs inside ONE transaction and ROLLS EVERYTHING BACK at the end.
--   * Fixture users are obviously fake, use an invalid hash string, exist only
--     for milliseconds inside the rolled-back transaction, and are NOT seed data.
--
-- RUN (from the repository root, after migrations were applied to cpdts_test):
--   powershell -File scripts\run_db.ps1 -Task test -Database test
-- Exit code 0 = all tests passed; non-zero = at least one test failed.
--
-- Coverage of the four required areas:
--   company isolation ....... T06, T07, T08
--   same-customer allocation . T09a, T09b
--   self-verification block .. T04
--   positive amounts ......... T02, T03a, T03b, T12

\set ON_ERROR_STOP on

-- Guard: never run against cpdts_dev, cpdts, production, or anything else.
DO $$
BEGIN
    IF current_database() <> 'cpdts_test' THEN
        RAISE EXCEPTION 'REFUSING TO RUN: these tests are only allowed on cpdts_test, current database is "%"',
            current_database();
    END IF;
END $$;

\echo '=== CPDTS migration constraint tests (rolled back at the end) ==='

BEGIN;

\echo 'T00: Flyway history is clean'
DO $$
DECLARE v_failed integer; v_applied integer;
BEGIN
    SELECT count(*) INTO v_failed  FROM flyway_schema_history WHERE success = false;
    SELECT count(*) INTO v_applied FROM flyway_schema_history WHERE success = true;
    IF v_failed > 0 THEN
        RAISE EXCEPTION 'TEST FAILED: % failed migration(s) recorded in flyway_schema_history', v_failed;
    END IF;
    IF v_applied < 5 THEN
        RAISE EXCEPTION 'TEST FAILED: expected at least 5 applied migrations, found %', v_applied;
    END IF;
    RAISE NOTICE 'T00 PASS: % applied migrations, 0 failed', v_applied;
END $$;

\echo 'T01: reference data - JMX and KAM seeded exactly once'
DO $$
DECLARE v_count integer;
BEGIN
    SELECT count(*) INTO v_count FROM companies WHERE code IN ('JMX', 'KAM');
    IF v_count <> 2 THEN
        RAISE EXCEPTION 'TEST FAILED: expected exactly 2 seeded companies, found %', v_count;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM companies WHERE code = 'JMX')
       OR NOT EXISTS (SELECT 1 FROM companies WHERE code = 'KAM') THEN
        RAISE EXCEPTION 'TEST FAILED: JMX and/or KAM codes missing';
    END IF;
    IF EXISTS (SELECT 1 FROM users WHERE email NOT LIKE '%@example.test') THEN
        RAISE EXCEPTION 'TEST FAILED: migrations must never seed users, but users outside the reserved test domain exist';
    END IF;
    -- Rows inside the reserved @example.test domain belong to the integration
    -- test suite (data-ownership rule), not to migrations. Audit actors among
    -- them are immortal by design: the append-only audit_logs references them,
    -- so they persist in this scratch database across runs.
    RAISE NOTICE 'T01 PASS: JMX + KAM present, no migration-seeded users';
END $$;

\echo 'T02: payment amounts must be positive'
DO $$
DECLARE v_company BIGINT; v_customer BIGINT; v_user BIGINT;
BEGIN
    SELECT id INTO v_company FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name)
    VALUES (v_company, 'T02 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t02@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0001', 'T02 Fixture')
    RETURNING id INTO v_user;

    BEGIN
        INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
        VALUES (v_company, v_customer, 'T02-PAY-ZERO', 0.00, 'CASH', now(), v_user);
        RAISE EXCEPTION 'TEST FAILED: zero-amount payment was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T02a PASS: zero payment amount rejected';
    END;

    BEGIN
        INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
        VALUES (v_company, v_customer, 'T02-PAY-NEG', -100.00, 'BANK_TRANSFER', now(), v_user);
        RAISE EXCEPTION 'TEST FAILED: negative payment amount was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T02b PASS: negative payment amount rejected';
    END;
END $$;

\echo 'T03: invoice item quantities/prices must be non-zero/non-negative; line_total is generated'
DO $$
DECLARE v_company BIGINT; v_customer BIGINT; v_invoice BIGINT; v_line NUMERIC(16,2);
BEGIN
    SELECT id INTO v_company FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name)
    VALUES (v_company, 'T03 Customer') RETURNING id INTO v_customer;
    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date)
    VALUES (v_company, v_customer, 'T03-INV', CURRENT_DATE, CURRENT_DATE + 7)
    RETURNING id INTO v_invoice;

    BEGIN
        INSERT INTO invoice_items (company_id, invoice_id, description, quantity, unit_price)
        VALUES (v_company, v_invoice, 'Zero quantity', 0, 1000.00);
        RAISE EXCEPTION 'TEST FAILED: zero quantity was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T03a PASS: zero quantity rejected';
    END;

    BEGIN
        INSERT INTO invoice_items (company_id, invoice_id, description, quantity, unit_price)
        VALUES (v_company, v_invoice, 'Negative price', 1, -1.00);
        RAISE EXCEPTION 'TEST FAILED: negative unit price was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T03b PASS: negative unit price rejected';
    END;

    INSERT INTO invoice_items (company_id, invoice_id, description, quantity, unit_price)
    VALUES (v_company, v_invoice, 'Line total check', 3, 123.45)
    RETURNING line_total INTO v_line;
    IF v_line <> 370.35 THEN
        RAISE EXCEPTION 'TEST FAILED: generated line_total expected 370.35, got %', v_line;
    END IF;
    RAISE NOTICE 'T03c PASS: line_total generated by database (3 x 123.45 = %)', v_line;
END $$;

\echo 'T04: the recorder can never verify their own payment'
DO $$
DECLARE v_company BIGINT; v_customer BIGINT; v_recorder BIGINT; v_payment BIGINT;
BEGIN
    SELECT id INTO v_company FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name)
    VALUES (v_company, 'T04 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t04.recorder@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0004', 'T04 Recorder')
    RETURNING id INTO v_recorder;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
    VALUES (v_company, v_customer, 'T04-PAY', 10000.00, 'CASH', now(), v_recorder)
    RETURNING id INTO v_payment;

    BEGIN
        INSERT INTO payment_verifications (company_id, payment_id, verifier_id, decision, reason)
        VALUES (v_company, v_payment, v_recorder, 'VERIFIED', 'attempting self-verification');
        RAISE EXCEPTION 'TEST FAILED: recorder was allowed to verify their own payment';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T04 PASS: self-verification rejected by database trigger';
    END;
END $$;

\echo 'T05: rejection requires a reason; a different user CAN verify'
DO $$
DECLARE v_company BIGINT; v_customer BIGINT; v_recorder BIGINT; v_verifier BIGINT; v_payment BIGINT;
BEGIN
    SELECT id INTO v_company FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name)
    VALUES (v_company, 'T05 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t05.recorder@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0005', 'T05 Recorder')
    RETURNING id INTO v_recorder;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t05.verifier@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0006', 'T05 Verifier')
    RETURNING id INTO v_verifier;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, reference_number, received_at, recorded_by)
    VALUES (v_company, v_customer, 'T05-PAY', 25000.00, 'MOBILE_MONEY', 'MP250101', now(), v_recorder)
    RETURNING id INTO v_payment;

    BEGIN
        INSERT INTO payment_verifications (company_id, payment_id, verifier_id, decision, reason)
        VALUES (v_company, v_payment, v_verifier, 'REJECTED', NULL);
        RAISE EXCEPTION 'TEST FAILED: REJECTED decision without reason was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T05a PASS: rejection without reason rejected';
    END;

    INSERT INTO payment_verifications (company_id, payment_id, verifier_id, decision, reason)
    VALUES (v_company, v_payment, v_verifier, 'VERIFIED', 'control: allowed by database');
    RAISE NOTICE 'T05b PASS: verification by a different user accepted';
END $$;

\echo 'T06: company isolation - an invoice cannot reference another company''s customer'
DO $$
DECLARE v_jmx BIGINT; v_kam BIGINT; v_kam_customer BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    SELECT id INTO v_kam FROM companies WHERE code = 'KAM';
    INSERT INTO customers (company_id, display_name)
    VALUES (v_kam, 'T06 KAM Customer') RETURNING id INTO v_kam_customer;

    BEGIN
        INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date)
        VALUES (v_jmx, v_kam_customer, 'T06-INV', CURRENT_DATE, CURRENT_DATE + 7);
        RAISE EXCEPTION 'TEST FAILED: JMX invoice linked to a KAM customer';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'T06 PASS: cross-company invoice rejected by composite foreign key';
    END;
END $$;

\echo 'T07: company isolation - a payment cannot reference another company''s customer'
DO $$
DECLARE v_jmx BIGINT; v_kam BIGINT; v_kam_customer BIGINT; v_user BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    SELECT id INTO v_kam FROM companies WHERE code = 'KAM';
    INSERT INTO customers (company_id, display_name)
    VALUES (v_kam, 'T07 KAM Customer') RETURNING id INTO v_kam_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t07@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0007', 'T07 Fixture')
    RETURNING id INTO v_user;

    BEGIN
        INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
        VALUES (v_jmx, v_kam_customer, 'T07-PAY', 5000.00, 'CASH', now(), v_user);
        RAISE EXCEPTION 'TEST FAILED: JMX payment linked to a KAM customer';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'T07 PASS: cross-company payment rejected by composite foreign key';
    END;
END $$;

\echo 'T08: company isolation - allocation cannot cross companies'
DO $$
DECLARE
    v_jmx BIGINT; v_kam BIGINT; v_customer BIGINT; v_recorder BIGINT; v_verifier BIGINT;
    v_payment BIGINT; v_invoice BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    SELECT id INTO v_kam FROM companies WHERE code = 'KAM';
    INSERT INTO customers (company_id, display_name)
    VALUES (v_jmx, 'T08 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t08.recorder@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0008', 'T08 Recorder')
    RETURNING id INTO v_recorder;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t08.verifier@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0009', 'T08 Verifier')
    RETURNING id INTO v_verifier;
    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date, status, total_amount)
    VALUES (v_jmx, v_customer, 'T08-INV', CURRENT_DATE, CURRENT_DATE + 7, 'ISSUED', 10000.00)
    RETURNING id INTO v_invoice;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
    VALUES (v_jmx, v_customer, 'T08-PAY', 10000.00, 'CASH', now(), v_recorder)
    RETURNING id INTO v_payment;
    INSERT INTO payment_verifications (company_id, payment_id, verifier_id, decision, reason)
    VALUES (v_jmx, v_payment, v_verifier, 'VERIFIED', 'setup for T08');
    UPDATE payments SET status = 'VERIFIED' WHERE id = v_payment;

    BEGIN
        INSERT INTO payment_allocations (company_id, customer_id, payment_id, invoice_id, amount, created_by)
        VALUES (v_kam, v_customer, v_payment, v_invoice, 10000.00, v_recorder);
        RAISE EXCEPTION 'TEST FAILED: allocation crossed company boundaries (KAM scope on JMX records)';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'T08 PASS: cross-company allocation rejected by composite foreign keys';
    END;
END $$;

\echo 'T09: a payment can only be allocated to ITS OWN customer''s invoices'
DO $$
DECLARE
    v_jmx BIGINT; v_customer_a BIGINT; v_customer_b BIGINT;
    v_recorder BIGINT; v_verifier BIGINT; v_payment BIGINT; v_invoice_b BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name) VALUES (v_jmx, 'T09 Customer A') RETURNING id INTO v_customer_a;
    INSERT INTO customers (company_id, display_name) VALUES (v_jmx, 'T09 Customer B') RETURNING id INTO v_customer_b;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t09.recorder@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0010', 'T09 Recorder')
    RETURNING id INTO v_recorder;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t09.verifier@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0011', 'T09 Verifier')
    RETURNING id INTO v_verifier;
    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date, status, total_amount)
    VALUES (v_jmx, v_customer_b, 'T09-INV-B', CURRENT_DATE, CURRENT_DATE + 7, 'ISSUED', 8000.00)
    RETURNING id INTO v_invoice_b;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
    VALUES (v_jmx, v_customer_a, 'T09-PAY', 8000.00, 'CASH', now(), v_recorder)
    RETURNING id INTO v_payment;
    INSERT INTO payment_verifications (company_id, payment_id, verifier_id, decision, reason)
    VALUES (v_jmx, v_payment, v_verifier, 'VERIFIED', 'setup for T09');
    UPDATE payments SET status = 'VERIFIED' WHERE id = v_payment;

    -- T09a: allocation claims customer A (payment side) against customer B's invoice.
    BEGIN
        INSERT INTO payment_allocations (company_id, customer_id, payment_id, invoice_id, amount, created_by)
        VALUES (v_jmx, v_customer_a, v_payment, v_invoice_b, 1000.00, v_recorder);
        RAISE EXCEPTION 'TEST FAILED: payment of customer A was allocated to an invoice of customer B';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'T09a PASS: allocation to another customer''s invoice rejected';
    END;

    -- T09b: allocation claims customer B (invoice side) against a payment of customer A.
    BEGIN
        INSERT INTO payment_allocations (company_id, customer_id, payment_id, invoice_id, amount, created_by)
        VALUES (v_jmx, v_customer_b, v_payment, v_invoice_b, 1000.00, v_recorder);
        RAISE EXCEPTION 'TEST FAILED: allocation accepted although payment customer differs from invoice customer';
    EXCEPTION WHEN foreign_key_violation THEN
        RAISE NOTICE 'T09b PASS: payment/invoice customer mismatch rejected';
    END;
END $$;

\echo 'T10: allocations require a VERIFIED payment'
DO $$
DECLARE
    v_jmx BIGINT; v_customer BIGINT; v_recorder BIGINT;
    v_payment BIGINT; v_invoice BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name) VALUES (v_jmx, 'T10 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t10@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0012', 'T10 Fixture')
    RETURNING id INTO v_recorder;
    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date, status, total_amount)
    VALUES (v_jmx, v_customer, 'T10-INV', CURRENT_DATE, CURRENT_DATE + 7, 'ISSUED', 4000.00)
    RETURNING id INTO v_invoice;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
    VALUES (v_jmx, v_customer, 'T10-PAY', 4000.00, 'CASH', now(), v_recorder)
    RETURNING id INTO v_payment;

    BEGIN
        INSERT INTO payment_allocations (company_id, customer_id, payment_id, invoice_id, amount, created_by)
        VALUES (v_jmx, v_customer, v_payment, v_invoice, 4000.00, v_recorder);
        RAISE EXCEPTION 'TEST FAILED: allocation accepted although payment is still PENDING_VERIFICATION';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T10 PASS: allocation on a non-VERIFIED payment rejected by trigger';
    END;
END $$;

\echo 'T11: positive control - full valid chain (record, verify by other user, allocate twice)'
DO $$
DECLARE
    v_jmx BIGINT; v_customer BIGINT; v_recorder BIGINT; v_verifier BIGINT;
    v_invoice1 BIGINT; v_invoice2 BIGINT; v_payment BIGINT; v_alloc BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name, customer_code)
    VALUES (v_jmx, 'T11 Customer', 'T11-CUST') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t11.recorder@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0013', 'T11 Recorder')
    RETURNING id INTO v_recorder;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t11.verifier@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0014', 'T11 Verifier')
    RETURNING id INTO v_verifier;

    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date, status, total_amount)
    VALUES (v_jmx, v_customer, 'T11-INV-1', CURRENT_DATE, CURRENT_DATE + 7, 'ISSUED', 6000.00)
    RETURNING id INTO v_invoice1;
    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date, status, total_amount)
    VALUES (v_jmx, v_customer, 'T11-INV-2', CURRENT_DATE, CURRENT_DATE + 30, 'ISSUED', 4000.00)
    RETURNING id INTO v_invoice2;
    INSERT INTO invoice_items (company_id, invoice_id, description, quantity, unit_price)
    VALUES (v_jmx, v_invoice1, 'T11 item', 2, 3000.00);

    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, reference_number, received_at, recorded_by)
    VALUES (v_jmx, v_customer, 'T11-PAY', 7000.00, 'BANK_TRANSFER', 'FT-2026-0001', now(), v_recorder)
    RETURNING id INTO v_payment;

    INSERT INTO payment_verifications (company_id, payment_id, verifier_id, decision, reason)
    VALUES (v_jmx, v_payment, v_verifier, 'VERIFIED', 'evidence reviewed manually');
    UPDATE payments SET status = 'VERIFIED' WHERE id = v_payment;

    -- Partial allocation across two invoices of the SAME customer (approved design).
    INSERT INTO payment_allocations (company_id, customer_id, payment_id, invoice_id, amount, created_by)
    VALUES (v_jmx, v_customer, v_payment, v_invoice1, 6000.00, v_recorder)
    RETURNING id INTO v_alloc;
    INSERT INTO payment_allocations (company_id, customer_id, payment_id, invoice_id, amount, created_by)
    VALUES (v_jmx, v_customer, v_payment, v_invoice2, 1000.00, v_recorder);

    -- The remaining 0.00 unallocated would be computed by the backend; here we
    -- only prove the multi-invoice allocation path is accepted by the database.
    RAISE NOTICE 'T11 PASS: valid chain accepted (record -> verify by other user -> allocate to 2 invoices)';
END $$;

\echo 'T12: allocation amounts must be positive'
DO $$
DECLARE
    v_jmx BIGINT; v_customer BIGINT; v_recorder BIGINT; v_verifier BIGINT;
    v_payment BIGINT; v_invoice BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name) VALUES (v_jmx, 'T12 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t12.recorder@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0015', 'T12 Recorder')
    RETURNING id INTO v_recorder;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t12.verifier@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0016', 'T12 Verifier')
    RETURNING id INTO v_verifier;
    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date, status, total_amount)
    VALUES (v_jmx, v_customer, 'T12-INV', CURRENT_DATE, CURRENT_DATE + 7, 'ISSUED', 5000.00)
    RETURNING id INTO v_invoice;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
    VALUES (v_jmx, v_customer, 'T12-PAY', 5000.00, 'CASH', now(), v_recorder)
    RETURNING id INTO v_payment;
    INSERT INTO payment_verifications (company_id, payment_id, verifier_id, decision, reason)
    VALUES (v_jmx, v_payment, v_verifier, 'VERIFIED', 'setup for T12');
    UPDATE payments SET status = 'VERIFIED' WHERE id = v_payment;

    BEGIN
        INSERT INTO payment_allocations (company_id, customer_id, payment_id, invoice_id, amount, created_by)
        VALUES (v_jmx, v_customer, v_payment, v_invoice, 0.00, v_recorder);
        RAISE EXCEPTION 'TEST FAILED: zero-amount allocation was accepted';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T12 PASS: zero-amount allocation rejected';
    END;
END $$;

\echo 'T13: audit_logs are append-only (no UPDATE, no DELETE)'
DO $$
DECLARE v_company BIGINT; v_audit BIGINT;
BEGIN
    SELECT id INTO v_company FROM companies WHERE code = 'JMX';
    INSERT INTO audit_logs (company_id, action, entity_type, entity_id, after_data, request_id)
    VALUES (v_company, 'TEST.EVENT', 'test_entity', 1, '{"result": "created"}'::jsonb, 'req-t13')
    RETURNING id INTO v_audit;

    BEGIN
        UPDATE audit_logs SET action = 'TAMPERED' WHERE id = v_audit;
        RAISE EXCEPTION 'TEST FAILED: audit_logs UPDATE was allowed';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'T13a PASS: audit_logs UPDATE blocked';
    END;

    BEGIN
        DELETE FROM audit_logs WHERE id = v_audit;
        RAISE EXCEPTION 'TEST FAILED: audit_logs DELETE was allowed';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'T13b PASS: audit_logs DELETE blocked';
    END;
END $$;

\echo 'T14: verification history is append-only'
DO $$
DECLARE
    v_jmx BIGINT; v_customer BIGINT; v_recorder BIGINT; v_verifier BIGINT;
    v_payment BIGINT; v_verification BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name) VALUES (v_jmx, 'T14 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t14.recorder@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0017', 'T14 Recorder')
    RETURNING id INTO v_recorder;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t14.verifier@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0018', 'T14 Verifier')
    RETURNING id INTO v_verifier;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
    VALUES (v_jmx, v_customer, 'T14-PAY', 1500.00, 'CASH', now(), v_recorder)
    RETURNING id INTO v_payment;
    INSERT INTO payment_verifications (company_id, payment_id, verifier_id, decision, reason)
    VALUES (v_jmx, v_payment, v_verifier, 'REJECTED', 'incorrect reference')
    RETURNING id INTO v_verification;

    BEGIN
        UPDATE payment_verifications SET decision = 'VERIFIED' WHERE id = v_verification;
        RAISE EXCEPTION 'TEST FAILED: verification history UPDATE was allowed';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'T14a PASS: verification history UPDATE blocked';
    END;

    BEGIN
        DELETE FROM payment_verifications WHERE id = v_verification;
        RAISE EXCEPTION 'TEST FAILED: verification history DELETE was allowed';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'T14b PASS: verification history DELETE blocked';
    END;
END $$;

\echo 'T15: payments are never hard-deleted'
DO $$
DECLARE v_jmx BIGINT; v_customer BIGINT; v_user BIGINT; v_payment BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name) VALUES (v_jmx, 'T15 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t15@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0019', 'T15 Fixture')
    RETURNING id INTO v_user;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
    VALUES (v_jmx, v_customer, 'T15-PAY', 700.00, 'CASH', now(), v_user)
    RETURNING id INTO v_payment;

    BEGIN
        DELETE FROM payments WHERE id = v_payment;
        RAISE EXCEPTION 'TEST FAILED: payment DELETE was allowed';
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE NOTICE 'T15 PASS: payment DELETE blocked (reversal workflow must be used)';
    END;
END $$;

\echo 'T16: reversal requires reason + actor + timestamp'
DO $$
DECLARE v_jmx BIGINT; v_customer BIGINT; v_recorder BIGINT; v_payment BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    INSERT INTO customers (company_id, display_name) VALUES (v_jmx, 'T16 Customer') RETURNING id INTO v_customer;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t16@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0020', 'T16 Fixture')
    RETURNING id INTO v_recorder;
    INSERT INTO payments (company_id, customer_id, payment_number, amount, method, received_at, recorded_by)
    VALUES (v_jmx, v_customer, 'T16-PAY', 900.00, 'CASH', now(), v_recorder)
    RETURNING id INTO v_payment;

    BEGIN
        UPDATE payments SET status = 'REVERSED' WHERE id = v_payment;
        RAISE EXCEPTION 'TEST FAILED: REVERSED accepted without reason/actor/timestamp';
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'T16a PASS: REVERSED without reason/actor/timestamp rejected';
    END;

    UPDATE payments
    SET status = 'REVERSED',
        reversed_at = now(),
        reversed_by = v_recorder,
        reversal_reason = 'test: completed reversal data is accepted'
    WHERE id = v_payment;
    RAISE NOTICE 'T16b PASS: REVERSED with reason + actor + timestamp accepted';
END $$;

\echo 'T17: invoice numbers are unique per company (but may repeat across companies)'
DO $$
DECLARE v_jmx BIGINT; v_kam BIGINT; v_customer_jmx BIGINT; v_customer_kam BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    SELECT id INTO v_kam FROM companies WHERE code = 'KAM';
    INSERT INTO customers (company_id, display_name) VALUES (v_jmx, 'T17 JMX Customer') RETURNING id INTO v_customer_jmx;
    INSERT INTO customers (company_id, display_name) VALUES (v_kam, 'T17 KAM Customer') RETURNING id INTO v_customer_kam;

    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date)
    VALUES (v_jmx, v_customer_jmx, 'T17-INV', CURRENT_DATE, CURRENT_DATE + 7);

    -- Same number in the OTHER company must be fine (company-scoped uniqueness).
    INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date)
    VALUES (v_kam, v_customer_kam, 'T17-INV', CURRENT_DATE, CURRENT_DATE + 7);
    RAISE NOTICE 'T17a PASS: same invoice number allowed in a different company';

    BEGIN
        INSERT INTO invoices (company_id, customer_id, invoice_number, issue_date, due_date)
        VALUES (v_jmx, v_customer_jmx, 'T17-INV', CURRENT_DATE, CURRENT_DATE + 7);
        RAISE EXCEPTION 'TEST FAILED: duplicate invoice number within one company was accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'T17b PASS: duplicate invoice number within company rejected';
    END;
END $$;

\echo 'T18: one membership per (company, user)'
DO $$
DECLARE v_jmx BIGINT; v_user BIGINT; v_role BIGINT; v_membership BIGINT;
BEGIN
    SELECT id INTO v_jmx FROM companies WHERE code = 'JMX';
    INSERT INTO roles (code, name)
    VALUES ('TEST_ROLE', 'Test fixture role') RETURNING id INTO v_role;
    INSERT INTO users (email, password_hash, full_name)
    VALUES ('t18@cpdts.test', 'TEST-FIXTURE-NOT-A-VALID-HASH-0021', 'T18 Fixture')
    RETURNING id INTO v_user;

    INSERT INTO company_memberships (company_id, user_id, role_id, status)
    VALUES (v_jmx, v_user, v_role, 'ACTIVE')
    RETURNING id INTO v_membership;

    BEGIN
        INSERT INTO company_memberships (company_id, user_id, role_id, status)
        VALUES (v_jmx, v_user, v_role, 'ACTIVE');
        RAISE EXCEPTION 'TEST FAILED: duplicate membership for the same (company, user) was accepted';
    EXCEPTION WHEN unique_violation THEN
        RAISE NOTICE 'T18 PASS: duplicate membership rejected';
    END;
END $$;

ROLLBACK;

\echo '=== ALL TESTS PASSED (transaction rolled back, no data left behind) ==='
