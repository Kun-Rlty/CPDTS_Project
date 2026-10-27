-- V2__create_customer_and_invoice_tables.sql
--
-- Customers, invoices and invoice line items with company-isolation enforced
-- at the database level:
--   * Every tenant table carries NOT NULL company_id.
--   * Every tenant table exposes UNIQUE (id, company_id) so later tables can use
--     composite foreign keys that physically prevent cross-company references.
--   * Invoice/customer relationships use a composite FK (customer_id, company_id).
-- Approved design decisions applied here:
--   * Invoice totals are stored (total_amount NUMERIC(14,2)) and are to be
--     calculated by the BACKEND from invoice_items within the same transaction;
--     this migration never computes or updates totals.
--   * Derived states (PAID / PARTIALLY PAID / OVERDUE) are NOT stored;
--     only the lifecycle statuses DRAFT, ISSUED, VOIDED are stored.

CREATE TABLE customers (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id    BIGINT       NOT NULL,
    customer_code VARCHAR(32),
    display_name  VARCHAR(255) NOT NULL,
    phone         VARCHAR(50),
    email         VARCHAR(320),
    address       VARCHAR(500),
    notes         TEXT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_by    BIGINT,
    -- Enables composite foreign keys from all tenant tables (cross-company guard).
    CONSTRAINT uq_customers_id_company   UNIQUE (id, company_id),
    -- Customer codes are unique within a company; NULL codes are allowed
    -- (multiple NULLs do not conflict).
    CONSTRAINT uq_customers_company_code UNIQUE (company_id, customer_code),
    CONSTRAINT ck_customers_display_name CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_customers_status       CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_customers_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_customers_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_customers_updater FOREIGN KEY (updated_by) REFERENCES users (id)
);

-- Customers are deactivated, never deleted (PRD FR-03; audit history preserved).
CREATE TRIGGER tg_customers_no_delete
    BEFORE DELETE ON customers
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_mutation();

CREATE TABLE invoices (
    id             BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id     BIGINT        NOT NULL,
    customer_id    BIGINT        NOT NULL,
    invoice_number VARCHAR(32)   NOT NULL,
    issue_date     DATE          NOT NULL,
    due_date       DATE          NOT NULL,
    currency_code  VARCHAR(3)    NOT NULL DEFAULT 'TZS',
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    -- Stored, backend-calculated total (server is authoritative; never accepted
    -- from the client). NUMERIC(14,2): exact decimal, no floating point.
    total_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,
    description    VARCHAR(1000),
    issued_at      TIMESTAMPTZ,
    voided_at      TIMESTAMPTZ,
    void_reason    TEXT,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     BIGINT,
    updated_by     BIGINT,
    -- Composite tenant keys: the 2-column key is the exact-match target for
    -- invoice_items FK (invoice_id, company_id); the 3-column key is the
    -- exact-match target for payment_allocations FK (invoice_id, company_id,
    -- customer_id). PostgreSQL requires identical column lists for each
    -- composite foreign-key reference.
    CONSTRAINT uq_invoices_id_company          UNIQUE (id, company_id),
    CONSTRAINT uq_invoices_id_company_customer UNIQUE (id, company_id, customer_id),
    -- Invoice numbers are unique within a company (JMX-INV-00001 style numbers).
    CONSTRAINT uq_invoices_company_number      UNIQUE (company_id, invoice_number),
    CONSTRAINT ck_invoices_dates    CHECK (due_date >= issue_date),
    CONSTRAINT ck_invoices_status   CHECK (status IN ('DRAFT', 'ISSUED', 'VOIDED')),
    CONSTRAINT ck_invoices_total    CHECK (total_amount >= 0),
    -- MVP is single-currency; relax only through an approved forward migration.
    CONSTRAINT ck_invoices_currency CHECK (currency_code = 'TZS'),
    -- Voids require timestamp + reason (audit history, no silent changes).
    CONSTRAINT ck_invoices_voided CHECK (
        (status = 'VOIDED'
            AND voided_at IS NOT NULL
            AND void_reason IS NOT NULL
            AND btrim(void_reason) <> '')
        OR (status <> 'VOIDED'
            AND voided_at IS NULL
            AND void_reason IS NULL)
    ),
    -- Cross-company guard: the customer must belong to the SAME company
    -- as the invoice, enforced by PostgreSQL itself.
    CONSTRAINT fk_invoices_customer FOREIGN KEY (customer_id, company_id)
        REFERENCES customers (id, company_id),
    CONSTRAINT fk_invoices_company  FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_invoices_creator  FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_invoices_updater  FOREIGN KEY (updated_by) REFERENCES users (id)
);

-- Issued financial records are never hard-deleted; corrections use VOID (approved workflow).
CREATE TRIGGER tg_invoices_no_delete
    BEFORE DELETE ON invoices
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_mutation();

CREATE TABLE invoice_items (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id  BIGINT        NOT NULL,
    invoice_id  BIGINT        NOT NULL,
    description VARCHAR(500)  NOT NULL,
    quantity    NUMERIC(12,3) NOT NULL,
    unit_price  NUMERIC(14,2) NOT NULL,
    -- Line totals are computed by PostgreSQL (immutable expression), never by
    -- the client; the backend must use the same rounding rule (half away from
    -- zero, 2 decimals) when it recalculates invoice total_amount.
    line_total  NUMERIC(16,2)
        GENERATED ALWAYS AS (CAST(round(quantity * unit_price, 2) AS NUMERIC(16,2))) STORED,
    sort_order  INTEGER       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_invoice_items_quantity   CHECK (quantity > 0),
    CONSTRAINT ck_invoice_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_invoice_items_desc       CHECK (btrim(description) <> ''),
    CONSTRAINT fk_invoice_items_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_invoice_items_invoice FOREIGN KEY (invoice_id, company_id)
        REFERENCES invoices (id, company_id)
);

CREATE INDEX ix_customers_company_name      ON customers (company_id, display_name);
CREATE INDEX ix_invoices_company_customer   ON invoices (company_id, customer_id, due_date);
CREATE INDEX ix_invoices_company_status     ON invoices (company_id, status);
CREATE INDEX ix_invoice_items_invoice       ON invoice_items (invoice_id);
