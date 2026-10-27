-- V1__create_identity_and_company_tables.sql
--
-- CPDTS identity and tenancy tables.
-- Approved design decisions applied here:
--   * BIGINT identity primary keys.
--   * One role per company membership (unique company_id + user_id).
--   * is_platform_admin flag on users for the single platform administrator.
--   * No users, passwords or administrator accounts are seeded by ANY migration.
--     The first administrator is created later through an approved bootstrap process.
-- The role/permission CATALOGUE is not seeded either: the permission matrix is
-- still pending approval in the PRD (section 9).

-- Shared helper: blocks UPDATE/DELETE on append-only history tables
-- (payment_verifications, audit_logs) and DELETE on financial records
-- (payments, payment_allocations, invoices, customers).
CREATE OR REPLACE FUNCTION fn_prevent_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only table %.%: % is not permitted; use the approved reversal/void workflow instead',
        TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

CREATE TABLE companies (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(16) NOT NULL,
    legal_name   VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_companies_code        UNIQUE (code),
    CONSTRAINT ck_companies_code_format CHECK (code = btrim(upper(code))),
    CONSTRAINT ck_companies_status      CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_companies_names       CHECK (btrim(legal_name) <> '' AND btrim(display_name) <> '')
);

CREATE TABLE users (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email             VARCHAR(320) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    full_name         VARCHAR(255) NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    is_platform_admin BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at     TIMESTAMPTZ,
    CONSTRAINT uq_users_email      UNIQUE (email),
    CONSTRAINT ck_users_status     CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_users_full_name  CHECK (btrim(full_name) <> ''),
    -- Defence-in-depth against storing short/plaintext credentials.
    -- Real adaptive password hashes are far longer than 20 characters.
    CONSTRAINT ck_users_password_hash CHECK (length(btrim(password_hash)) >= 20)
);

CREATE TABLE roles (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(255),
    CONSTRAINT uq_roles_code UNIQUE (code),
    CONSTRAINT ck_roles_code CHECK (code = btrim(upper(code)))
);

CREATE TABLE permissions (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(128) NOT NULL,
    description VARCHAR(255),
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role       FOREIGN KEY (role_id)       REFERENCES roles (id),
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id)
);

CREATE TABLE company_memberships (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT      NOT NULL,
    user_id    BIGINT      NOT NULL,
    role_id    BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT,
    -- Approved: exactly one role per membership; at most one membership per
    -- (company, user). Multiple roles per company require an approved schema change.
    CONSTRAINT uq_memberships_company_user UNIQUE (company_id, user_id),
    CONSTRAINT ck_memberships_status       CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_memberships_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_memberships_user    FOREIGN KEY (user_id)    REFERENCES users (id),
    CONSTRAINT fk_memberships_role    FOREIGN KEY (role_id)    REFERENCES roles (id),
    CONSTRAINT fk_memberships_creator FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE INDEX ix_memberships_user_company_status
    ON company_memberships (user_id, company_id, status);
