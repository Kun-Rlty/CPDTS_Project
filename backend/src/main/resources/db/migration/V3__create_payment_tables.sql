-- V3__create_payment_tables.sql
--
-- Payment recording, manual verification history and invoice allocations.
-- Approved design decisions applied here:
--   * Each payment belongs to exactly one customer (customer_id NOT NULL).
--   * A payment may only be allocated to invoices of the SAME customer and
--     SAME company - enforced by PostgreSQL through composite foreign keys
--     on (payment_id, company_id, customer_id) and (invoice_id, company_id, customer_id).
--   * Full payment amounts are recorded; verified amounts not yet allocated
--     remain customer credit (derived: verified amount minus ACTIVE allocations).
--   * Only ACTIVE allocations of VERIFIED payments reduce invoice balances;
--     a database trigger refuses allocations against non-VERIFIED payments.
--   * The recorder can never verify/reject their own payment: database trigger
--     (defence in depth) plus the mandatory backend check.
--   * Payment methods: CASH, BANK_TRANSFER, MOBILE_MONEY. Verification is manual.
--   * Reversal of payments/allocations requires reason + actor + timestamp.
-- Payment evidence uploads are DEFERRED (approved): no evidence columns here.

-- Per-company counters for JMX-INV-00001 style business numbers.
CREATE TABLE number_sequences (
    company_id  BIGINT      NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    next_value  BIGINT      NOT NULL DEFAULT 1,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, kind),
    CONSTRAINT ck_number_sequences_kind CHECK (kind IN ('INVOICE', 'PAYMENT', 'CUSTOMER')),
    CONSTRAINT ck_number_sequences_next CHECK (next_value >= 1),
    CONSTRAINT fk_number_sequences_company FOREIGN KEY (company_id) REFERENCES companies (id)
);

CREATE TABLE payments (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id       BIGINT        NOT NULL,
    customer_id      BIGINT        NOT NULL,
    payment_number   VARCHAR(32)   NOT NULL,
    amount           NUMERIC(14,2) NOT NULL,
    currency_code    VARCHAR(3)    NOT NULL DEFAULT 'TZS',
    method           VARCHAR(16)   NOT NULL,
    -- Nullable: cash payments may have no external reference (PRD FR-05).
    reference_number VARCHAR(100),
    received_at      TIMESTAMPTZ   NOT NULL,
    recorded_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    status           VARCHAR(24)   NOT NULL DEFAULT 'PENDING_VERIFICATION',
    recorded_by      BIGINT        NOT NULL,
    notes            VARCHAR(1000),
    reversed_at      TIMESTAMPTZ,
    reversed_by      BIGINT,
    reversal_reason  TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Composite tenant keys: the 2-column key is the exact-match target for
    -- payment_verifications FK (payment_id, company_id); the 3-column key is
    -- the exact-match target for payment_allocations FK (payment_id,
    -- company_id, customer_id). PostgreSQL requires identical column lists.
    CONSTRAINT uq_payments_id_company          UNIQUE (id, company_id),
    CONSTRAINT uq_payments_id_company_customer UNIQUE (id, company_id, customer_id),
    -- Payment numbers are unique within a company (JMX-PAY-00001 style).
    CONSTRAINT uq_payments_company_number      UNIQUE (company_id, payment_number),
    CONSTRAINT ck_payments_amount   CHECK (amount > 0),
    CONSTRAINT ck_payments_method   CHECK (method IN ('CASH', 'BANK_TRANSFER', 'MOBILE_MONEY')),
    CONSTRAINT ck_payments_status   CHECK (status IN ('PENDING_VERIFICATION', 'VERIFIED', 'REJECTED', 'REVERSED')),
    -- MVP is single-currency; relax only through an approved forward migration.
    CONSTRAINT ck_payments_currency CHECK (currency_code = 'TZS'),
    -- Reversal requires reason + actor + timestamp (approved design);
    -- non-reversed payments must not carry any reversal data.
    CONSTRAINT ck_payments_reversed CHECK (
        (status = 'REVERSED'
            AND reversed_at IS NOT NULL
            AND reversed_by IS NOT NULL
            AND reversal_reason IS NOT NULL
            AND btrim(reversal_reason) <> '')
        OR (status <> 'REVERSED'
            AND reversed_at IS NULL
            AND reversed_by IS NULL
            AND reversal_reason IS NULL)
    ),
    -- Cross-company guard: payment customer must be in the same company.
    CONSTRAINT fk_payments_customer FOREIGN KEY (customer_id, company_id)
        REFERENCES customers (id, company_id),
    CONSTRAINT fk_payments_company  FOREIGN KEY (company_id)  REFERENCES companies (id),
    CONSTRAINT fk_payments_recorder FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT fk_payments_reverser FOREIGN KEY (reversed_by) REFERENCES users (id)
);

-- Payments are reversed, never hard-deleted (AGENTS.md rule 9 / PRD FR-06).
CREATE TRIGGER tg_payments_no_delete
    BEFORE DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_mutation();

CREATE INDEX ix_payments_company_status_received ON payments (company_id, status, received_at);
CREATE INDEX ix_payments_company_customer        ON payments (company_id, customer_id);
-- Supports duplicate-reference warnings/checks (non-unique on purpose:
-- duplicate-reference POLICY is still an open decision).
CREATE INDEX ix_payments_company_reference       ON payments (company_id, reference_number)
    WHERE reference_number IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Manual verification history: append-only, one row per decision.
CREATE TABLE payment_verifications (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT      NOT NULL,
    payment_id BIGINT      NOT NULL,
    verifier_id BIGINT     NOT NULL,
    decision   VARCHAR(16) NOT NULL,
    reason     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_verifications_decision CHECK (decision IN ('VERIFIED', 'REJECTED')),
    -- Rejections must carry a reason (APP_FLOW section 7 / PRD FR-06).
    CONSTRAINT ck_payment_verifications_reason CHECK (
        decision <> 'REJECTED'
        OR (reason IS NOT NULL AND btrim(reason) <> '')
    ),
    CONSTRAINT fk_payment_verifications_company FOREIGN KEY (company_id)  REFERENCES companies (id),
    CONSTRAINT fk_payment_verifications_payment FOREIGN KEY (payment_id, company_id)
        REFERENCES payments (id, company_id),
    CONSTRAINT fk_payment_verifications_verifier FOREIGN KEY (verifier_id) REFERENCES users (id)
);

-- BUSINESS RULE: the person who recorded a payment can never verify or reject it.
-- This is a cross-row rule, so a plain CHECK cannot do it; a trigger can.
CREATE OR REPLACE FUNCTION fn_check_payment_verifier() RETURNS trigger AS $$
DECLARE
    v_recorded_by BIGINT;
BEGIN
    SELECT p.recorded_by INTO v_recorded_by
    FROM payments p
    WHERE p.id = NEW.payment_id AND p.company_id = NEW.company_id;

    IF v_recorded_by IS NULL THEN
        RAISE EXCEPTION 'payment % not found within company %', NEW.payment_id, NEW.company_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF v_recorded_by = NEW.verifier_id THEN
        RAISE EXCEPTION 'self-verification blocked: user % recorded payment % and cannot verify or reject it',
            NEW.verifier_id, NEW.payment_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_payment_verifications_verifier
    BEFORE INSERT ON payment_verifications
    FOR EACH ROW EXECUTE FUNCTION fn_check_payment_verifier();

-- Verification history is append-only: decisions are never edited or erased.
CREATE TRIGGER tg_payment_verifications_no_mutation
    BEFORE UPDATE OR DELETE ON payment_verifications
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_mutation();

-- ---------------------------------------------------------------------------
-- Allocations: many-to-many between payments and invoices.
-- The composite foreign keys below enforce BOTH rules in pure SQL:
--   1) allocation.company_id must match payment and invoice (no cross-company link);
--   2) payment.customer_id = invoice.customer_id = allocation.customer_id
--      (a payment can only settle invoices of ITS OWN customer).
CREATE TABLE payment_allocations (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id      BIGINT        NOT NULL,
    customer_id     BIGINT        NOT NULL,
    payment_id      BIGINT        NOT NULL,
    invoice_id      BIGINT        NOT NULL,
    amount          NUMERIC(14,2) NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      BIGINT        NOT NULL,
    reversed_at     TIMESTAMPTZ,
    reversed_by     BIGINT,
    reversal_reason TEXT,
    CONSTRAINT ck_payment_allocations_amount  CHECK (amount > 0),
    CONSTRAINT ck_payment_allocations_status  CHECK (status IN ('ACTIVE', 'REVERSED')),
    -- Allocation reversal requires reason + actor + timestamp (approved design).
    CONSTRAINT ck_payment_allocations_reversed CHECK (
        (status = 'REVERSED'
            AND reversed_at IS NOT NULL
            AND reversed_by IS NOT NULL
            AND reversal_reason IS NOT NULL
            AND btrim(reversal_reason) <> '')
        OR (status <> 'REVERSED'
            AND reversed_at IS NULL
            AND reversed_by IS NULL
            AND reversal_reason IS NULL)
    ),
    CONSTRAINT fk_allocations_payment FOREIGN KEY (payment_id, company_id, customer_id)
        REFERENCES payments (id, company_id, customer_id),
    CONSTRAINT fk_allocations_invoice FOREIGN KEY (invoice_id, company_id, customer_id)
        REFERENCES invoices (id, company_id, customer_id),
    CONSTRAINT fk_allocations_company FOREIGN KEY (company_id)  REFERENCES companies (id),
    CONSTRAINT fk_allocations_creator FOREIGN KEY (created_by)  REFERENCES users (id),
    CONSTRAINT fk_allocations_reverser FOREIGN KEY (reversed_by) REFERENCES users (id)
);

-- Only ACTIVE allocations of VERIFIED payments may ever exist.
-- (Balances additionally require the payment to be non-reversed; that
-- multi-table derivation stays in the backend, where it is recomputed.)
CREATE OR REPLACE FUNCTION fn_check_allocation_payment_verified() RETURNS trigger AS $$
DECLARE
    v_status payments.status%TYPE;
BEGIN
    SELECT p.status INTO v_status FROM payments p WHERE p.id = NEW.payment_id;

    IF v_status IS DISTINCT FROM 'VERIFIED' THEN
        RAISE EXCEPTION 'allocation blocked: payment % is %, only VERIFIED payments can be allocated',
            NEW.payment_id, v_status
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_allocations_require_verified_payment
    BEFORE INSERT ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION fn_check_allocation_payment_verified();

-- Allocations are reversed (with reason), never hard-deleted.
CREATE TRIGGER tg_allocations_no_delete
    BEFORE DELETE ON payment_allocations
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_mutation();

CREATE INDEX ix_payment_allocations_company_payment ON payment_allocations (company_id, payment_id);
CREATE INDEX ix_payment_allocations_company_invoice ON payment_allocations (company_id, invoice_id);
CREATE INDEX ix_payment_verifications_company_payment
    ON payment_verifications (company_id, payment_id, created_at);
