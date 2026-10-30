-- V7__block_invoice_allocations_outside_issued_status.sql
--
-- Defense in depth for the approved invoice lifecycle (Phase 5):
--   * An allocation may only ever target an ISSUED invoice. DRAFT invoices
--     are not collectible debt yet, and VOIDED invoices are terminal and
--     excluded from collectible debt, so neither can receive allocations.
--
-- The backend service layer already enforces this transactionally (the
-- allocation phase must lock the invoice row FOR UPDATE and re-check
-- status = 'ISSUED'; the void operation takes the same lock and rejects
-- invoices that carry ACTIVE allocations). This trigger mirrors that
-- contract at the database level in the same style as V3's
-- tg_allocations_require_verified_payment, so a future service-layer bug
-- can never silently corrupt derived balances.
--
-- Scope note: INSERT only. Allocation UPDATEs are limited to reversal
-- (status/reversal columns) by the approved reversal model, and the
-- append-only/no-delete triggers from V3 remain untouched.

CREATE OR REPLACE FUNCTION fn_check_allocation_invoice_issued() RETURNS trigger AS $$
DECLARE
    v_status invoices.status%TYPE;
BEGIN
    SELECT i.status INTO v_status
    FROM invoices i
    WHERE i.id = NEW.invoice_id AND i.company_id = NEW.company_id;

    IF v_status IS NULL THEN
        RAISE EXCEPTION 'invoice % not found within company %', NEW.invoice_id, NEW.company_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF v_status <> 'ISSUED' THEN
        RAISE EXCEPTION 'allocation blocked: invoice % is %, only ISSUED invoices accept allocations',
            NEW.invoice_id, v_status
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_allocations_require_issued_invoice
    BEFORE INSERT ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION fn_check_allocation_invoice_issued();
