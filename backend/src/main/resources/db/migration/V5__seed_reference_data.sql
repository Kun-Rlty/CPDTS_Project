-- V5__seed_reference_data.sql
--
-- Safe reference data only:
--   * The two approved companies: JMX and KAM.
--   * Per-company number sequence starting rows (structural, not policy).
--
-- Explicitly NOT seeded here (by instruction and by PRD section 9):
--   * No users of any kind.
--   * No passwords or credentials of any kind.
--   * No role/permission catalogue - still pending stakeholder approval.

INSERT INTO companies (code, legal_name, display_name, status)
VALUES
    ('JMX', 'JEIMAX INVESTMENTS COMPANY',
                'JEIMAX INVESTMENTS COMPANY', 'ACTIVE'),
    ('KAM', 'KAMWERUKA SECURITY & SAFETY ADMINISTRATOR CO. LTD.',
                'KAMWERUKA SECURITY & SAFETY ADMINISTRATOR CO. LTD.', 'ACTIVE');

-- Business-number counters for both companies:
-- JMX-INV-00001 / JMX-PAY-00001, KAM-INV-00001 / KAM-PAY-00001, ...
INSERT INTO number_sequences (company_id, kind, next_value)
SELECT c.id, k.kind, 1
FROM companies c
CROSS JOIN (VALUES ('INVOICE'), ('PAYMENT'), ('CUSTOMER')) AS k (kind)
WHERE c.code IN ('JMX', 'KAM');
