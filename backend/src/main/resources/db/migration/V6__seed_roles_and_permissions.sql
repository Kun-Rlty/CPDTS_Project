-- V6__seed_roles_and_permissions.sql
--
-- Approved role/permission catalogue (approved in the authentication
-- foundation task; PRD section 9 required this approval before seeding).
-- Safe reference data ONLY:
--   * 7 permission codes - the exact set the approved roles need, nothing more.
--   * 5 roles (roles are global in V1; company scoping happens through
--     company_memberships, so each role is seeded once, not per company).
--   * The approved role -> permission matrix (role_permissions).
--
-- Explicitly NOT seeded here (same rules as V1/V5):
--   * No users of any kind.
--   * No passwords or credentials of any kind.
--   * No company memberships.

-- ---------------------------------------------------------------------------
-- Permission catalogue
-- ---------------------------------------------------------------------------
INSERT INTO permissions (code, description)
VALUES
    ('USER_MANAGE',     'Create and update users and company memberships'),
    ('CUSTOMER_MANAGE', 'Create and update customers'),
    ('INVOICE_MANAGE',  'Create, issue and update invoices'),
    ('PAYMENT_RECORD',  'Record payments and payment allocations'),
    ('PAYMENT_VERIFY',  'Verify or reject recorded payments'),
    ('RECORD_VIEW',     'View customers, invoices, payments and allocations'),
    ('REPORT_VIEW',     'View reports and report data');

-- ---------------------------------------------------------------------------
-- Role catalogue (shared by JMX and KAM via company_memberships)
-- ---------------------------------------------------------------------------
INSERT INTO roles (code, name, description)
VALUES
    ('PLATFORM_ADMIN',   'Platform Admin',
        'Platform-wide administration across all companies'),
    ('COMPANY_ADMIN',    'Company Admin',
        'Manages users and work within their assigned company'),
    ('FINANCE_OFFICER',  'Finance Officer',
        'Manages customers, invoices and payment records; views reports'),
    ('PAYMENT_VERIFIER', 'Payment Verifier',
        'Reviews and verifies or rejects payments; views records and reports'),
    ('VIEWER',           'Viewer',
        'Read-only access to company records and reports');

-- ---------------------------------------------------------------------------
-- Approved permission matrix
--   PLATFORM_ADMIN    : all 7 permissions
--   COMPANY_ADMIN     : all 7 permissions
--   FINANCE_OFFICER   : CUSTOMER_MANAGE, INVOICE_MANAGE, PAYMENT_RECORD,
--                       RECORD_VIEW, REPORT_VIEW
--   PAYMENT_VERIFIER  : PAYMENT_VERIFY, RECORD_VIEW, REPORT_VIEW
--   VIEWER            : RECORD_VIEW, REPORT_VIEW
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
        ('PLATFORM_ADMIN',
            ARRAY['USER_MANAGE', 'CUSTOMER_MANAGE', 'INVOICE_MANAGE',
                  'PAYMENT_RECORD', 'PAYMENT_VERIFY', 'RECORD_VIEW',
                  'REPORT_VIEW']),
        ('COMPANY_ADMIN',
            ARRAY['USER_MANAGE', 'CUSTOMER_MANAGE', 'INVOICE_MANAGE',
                  'PAYMENT_RECORD', 'PAYMENT_VERIFY', 'RECORD_VIEW',
                  'REPORT_VIEW']),
        ('FINANCE_OFFICER',
            ARRAY['CUSTOMER_MANAGE', 'INVOICE_MANAGE', 'PAYMENT_RECORD',
                  'RECORD_VIEW', 'REPORT_VIEW']),
        ('PAYMENT_VERIFIER',
            ARRAY['PAYMENT_VERIFY', 'RECORD_VIEW', 'REPORT_VIEW']),
        ('VIEWER',
            ARRAY['RECORD_VIEW', 'REPORT_VIEW'])
     ) AS matrix (role_code, permission_codes)
JOIN roles r        ON r.code = matrix.role_code
JOIN LATERAL unnest(matrix.permission_codes) AS wanted (code) ON true
JOIN permissions p ON p.code = wanted.code;
