-- V8__add_payment_reversal_permission_and_idempotency_keys.sql
--
-- Additive migration for the payment-management phase (V1..V7 untouched).
-- Two approved decisions from the phase instruction are encoded here:
--
--   1) WHOLE-PAYMENT REVERSAL IS ADMIN-ONLY. The phase instruction limits
--      reversal to platform/company admins. That rule cannot reuse
--      PAYMENT_VERIFY (V6 also grants it to PAYMENT_VERIFIER), and the
--      architecture authorizes by permission, never by role name
--      (AccessControlService). So a dedicated permission PAYMENT_REVERSE is
--      added and granted ONLY to PLATFORM_ADMIN and COMPANY_ADMIN.
--      Platform admins additionally receive it through the code-level
--      bypass set (Permissions.ALL), which the permission-matrix test
--      asserts matches this catalogue exactly.
--
--   2) IDEMPOTENT PAYMENT CREATION AND ALLOCATION. Retried requests must
--      never duplicate financial entries. The client supplies an
--      Idempotency-Key header; the key row is written in the SAME
--      transaction as the mutation, and UNIQUE (company_id, scope,
--      key) makes a duplicate commit impossible. A retry with the SAME
--      request replays the stored entity; a retry with a DIFFERENT request
--      body under the same key is rejected. Keys are company-scoped
--      operational metadata (not financial history) - rows persist.
--
-- This migration was tested on cpdts_test (constraint suite + trigger
-- checks + full Java build) BEFORE being applied to cpdts_dev.

-- ---------------------------------------------------------------------------
-- 1) PAYMENT_REVERSE permission - admins only.
-- ---------------------------------------------------------------------------
INSERT INTO permissions (code, description)
VALUES
    ('PAYMENT_REVERSE', 'Reverse verified payments and their allocations with reason and audit');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'PAYMENT_REVERSE'
WHERE r.code IN ('PLATFORM_ADMIN', 'COMPANY_ADMIN');

-- ---------------------------------------------------------------------------
-- 2) Idempotency keys for payment creation and allocation.
--    Append-only operational record: one row per accepted (company, scope,
--    key) with a hash of the request for reuse detection.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id      BIGINT       NOT NULL,
    actor_user_id   BIGINT       NOT NULL,
    scope           VARCHAR(32)  NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    -- SHA-256 (hex) of the canonical request: same hash => replay,
    -- different hash under the same key => reject (409).
    request_hash    CHAR(64)     NOT NULL,
    entity_type     VARCHAR(32)  NOT NULL,
    entity_id       BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_idempotency_keys_scope CHECK (scope IN ('PAYMENT_CREATE', 'PAYMENT_ALLOCATE')),
    CONSTRAINT ck_idempotency_keys_key CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT uq_idempotency_keys_company_scope_key UNIQUE (company_id, scope, idempotency_key),
    CONSTRAINT fk_idempotency_keys_company FOREIGN KEY (company_id)    REFERENCES companies (id),
    CONSTRAINT fk_idempotency_keys_actor   FOREIGN KEY (actor_user_id) REFERENCES users (id)
);
