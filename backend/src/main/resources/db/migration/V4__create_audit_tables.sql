-- V4__create_audit_tables.sql
--
-- Append-only audit trail for sensitive and financial actions (PRD FR-09).
-- company_id is NULLABLE: platform-wide actions (e.g. privileged cross-company
-- access by the platform administrator) have no single company scope.
-- actor_user_id is NULLABLE: system-generated events may have no user actor.
-- The backend must never store secrets, passwords, tokens or raw payment
-- evidence content in before_data / after_data - only minimized change summaries.

CREATE TABLE audit_logs (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id    BIGINT,
    actor_user_id BIGINT,
    action        VARCHAR(64)  NOT NULL,
    entity_type   VARCHAR(64)  NOT NULL,
    entity_id     BIGINT,
    before_data   JSONB,
    after_data    JSONB,
    reason        TEXT,
    request_id    VARCHAR(64),
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_logs_action CHECK (btrim(action) <> ''),
    CONSTRAINT ck_audit_logs_entity CHECK (btrim(entity_type) <> ''),
    CONSTRAINT fk_audit_logs_company FOREIGN KEY (company_id)    REFERENCES companies (id),
    CONSTRAINT fk_audit_logs_actor   FOREIGN KEY (actor_user_id) REFERENCES users (id)
);

-- Audit records are protected from ordinary modification and deletion.
CREATE TRIGGER tg_audit_logs_no_mutation
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION fn_prevent_mutation();

CREATE INDEX ix_audit_logs_company_occurred ON audit_logs (company_id, occurred_at);
CREATE INDEX ix_audit_logs_entity           ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_logs_actor_occurred   ON audit_logs (actor_user_id, occurred_at);
