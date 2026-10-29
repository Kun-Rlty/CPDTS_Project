# CPDTS — Client Payment & Debt Tracking System

Starter workspace for JEIMAX INVESTMENTS COMPANY (`JMX`) and KAMWERUKA SECURITY & SAFETY ADMINISTRATOR CO. LTD. (`KAM`).

**Status:** Database foundation (Flyway V1–V6) and Spring Boot backend are implemented and tested locally. The authentication and company-access foundation, user & company-membership management APIs and company-scoped customer management are implemented (see below). Invoices, payments, reporting and the frontend are NOT implemented yet.

## Current phase: authentication, company access, user & customer management (implemented)

What is implemented and covered by automated tests (85 tests against the real `cpdts_test` database):

- **Session-based authentication (provisional decision):** login/logout via `POST/GET /api/v1/auth/*`; server-side HTTP sessions with BCrypt (cost 10) password hashes. JWT is NOT used; the token strategy in `docs/TECHNICAL_REQUIREMENTS.md` section 14 remains an open decision and sessions are the reversible default until that is approved.
- **UTF-8 password limit:** passwords are limited to 12–72 UTF-8 **bytes** (bcrypt's hard limit is byte-based). Over-long input is rejected up front — never silently truncated.
- **Session hygiene:** login rotates the session ID of any pre-login session (session-fixation protection); logout invalidates the session; user deactivation ends live sessions on the next request.
- **Permission matrix (migration V6):** 7 permissions (`USER_MANAGE`, `CUSTOMER_MANAGE`, `INVOICE_MANAGE`, `PAYMENT_RECORD`, `PAYMENT_VERIFY`, `RECORD_VIEW`, `REPORT_VIEW`) and 5 roles (`PLATFORM_ADMIN`, `COMPANY_ADMIN`, `FINANCE_OFFICER`, `PAYMENT_VERIFIER`, `VIEWER`). Authorization is permission-based (from `role_permissions`), not role-name-based.
- **Company access checks:** only ACTIVE memberships in ACTIVE companies grant permissions; users without a membership have no access to that company; the platform administrator (a `users.is_platform_admin` flag, set only by the one-time bootstrap) has every permission in both companies without memberships. Checks run server-side on every request (fresh from the database).
- **One-time platform-admin bootstrap:** `com.cpdts.bootstrap.PlatformAdminBootstrap` creates the first administrator with a privately read password (no-echo console; refuses without one unless explicitly overridden) and writes the admin record and its `PLATFORM_ADMIN_BOOTSTRAPPED` audit event in ONE database transaction. A second platform admin can never be created by this command. See "Creating the first platform administrator" below.
- **Database-enforced rules (migrations V1–V3):** one role per company membership; a payment's recorder can never verify it (trigger `tg_payment_verifications_verifier`); financial tables are append-only.
- **User and company-membership management APIs** (all permission-checked per target company with `USER_MANAGE`, CSRF-protected, audit-logged in the same transaction as the change):
  - `GET /api/v1/companies/{companyId}/users` — list a company's members (role, membership status, global user status; password hashes are never returned).
  - `POST /api/v1/companies/{companyId}/users` — create a new ACTIVE user with an ACTIVE membership (password via the existing `PasswordPolicy`, stored as BCrypt; `is_platform_admin` is always written FALSE), **or** attach an existing user to the company — platform administrator only, password must be absent, existing profile/credentials never touched. If the email is already registered anywhere in the system, a company admin receives a generic 409 conflict that does not reveal whether the account is in their own company or elsewhere.
  - `PATCH /api/v1/companies/{companyId}/users/{userId}/membership` — change the membership role and/or activate/deactivate it; effects are immediate (permissions are re-resolved from the database on every request).
  - Rules: platform admins manage both companies; company admins only their own (cross-company → 403, unknown company → 403 fail-closed). `PLATFORM_ADMIN` role cannot be assigned (403) and `is_platform_admin` is not writable through any API. Duplicate membership → 409; membership outside the addressed company → 404. Audit events: `USER_CREATED`, `MEMBERSHIP_CREATED`, `MEMBERSHIP_ROLE_CHANGED`, `MEMBERSHIP_STATUS_CHANGED` with the acting session user as actor.
- **Consistent email identity:** login, the bootstrap and user creation all normalize emails through one shared helper (trimmed, lowercased with `Locale.ROOT`), so the same address always resolves to a single account regardless of typing case. Live data in both databases was verified collision-free before this rule was enforced — no migration or account rewrite was needed.
- **Stable HTTP error contract:** unsupported methods on known endpoints return 405 with an `Allow` header and a JSON body (previously 500); authentication and CSRF checks still run before method routing, and non-numeric path variables return 400.
- **Company-scoped customer management APIs** (PRD FR-03; reads require `RECORD_VIEW`, mutations require `CUSTOMER_MANAGE`, all permission-checked per target company, CSRF-protected, audit-logged in the same transaction):
  - `GET /api/v1/companies/{companyId}/customers` — bounded pagination (`page` ≥ 0, 1 ≤ `size` ≤ 100, default 20) with optional `status` filter and `q` search over display name / customer code / email / phone; user input is ILIKE-escaped so `%`/`_`/`\` match literally; everything scoped to the authorized company.
  - `POST /api/v1/companies/{companyId}/customers` — creates an ACTIVE customer with a **server-generated code** (`JMX-CUST-00001` style, from the company's `number_sequences` row of kind `CUSTOMER`, locked and incremented inside the creation transaction so concurrent creates get distinct contiguous numbers). A client-supplied code or company is never read.
  - `GET /api/v1/companies/{companyId}/customers/{customerId}` — detail; a customer of another company is indistinguishable from a nonexistent one (404, no disclosure).
  - `PATCH /api/v1/companies/{companyId}/customers/{customerId}` — update fields and/or `status` (activate/deactivate); null means unchanged, empty string clears a nullable field; only provided fields are changed, and the audit event carries the diff of the changed fields only.
  - Rules: customers are **never deleted** (no DELETE endpoint; a database trigger also blocks it) — deactivation is a status change. Customer codes follow the approved `JMX-INV-00001` numbering convention with a `CUST` segment, derived from migrations V3/V5. Customers are separate company-scoped records (migration V2: `company_id NOT NULL`), so the same party in both companies is two rows. Audit events: `CUSTOMER_CREATED` and `CUSTOMER_UPDATED` with the acting session user as actor.

What is NOT implemented yet (future approved phases):

- **Global management of existing users** — no global user directory, no global user status change, profile edit, password change/reset or account recovery (deliberate scope decision: company admins must not reach global account data; only the bootstrap writes `is_platform_admin`).
- **Session revocation lists, multi-session limits, account recovery, HTTPS-only cookie enforcement in production** — required before production per `docs/TECHNICAL_REQUIREMENTS.md`.
- **Payment-service layer enforcement of recorder ≠ verifier** — the database trigger already blocks it; the service-level check arrives with the payments phase.
- Invoices, payments, reporting, frontend.

## Creating the first platform administrator

Run from an **interactive terminal** (required so the password can be read without echo). Set the database variables from your local `.env.backend` WITHOUT printing them, then launch the bootstrap main class:

```powershell
# from the repository root
$kv = @{}
Get-Content .\.env.backend | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $p = $line -split '=', 2
        if ($p.Count -eq 2) { $kv[$p[0].Trim()] = $p[1].Trim() }
    }
}
$env:CPDTS_DB_URL  = "jdbc:postgresql://$(if ($kv.DB_HOST) { $kv.DB_HOST } else { 'localhost' }):$(if ($kv.DB_PORT) { $kv.DB_PORT } else { '5432' })/$(if ($kv.DB_NAME) { $kv.DB_NAME } else { 'cpdts_dev' })"
$env:CPDTS_DB_USER = $kv.DB_USER
$env:CPDTS_DB_PASSWORD = $kv.DB_PASSWORD

mvn -f backend\pom.xml spring-boot:run "-Dspring-boot.run.main-class=com.cpdts.bootstrap.PlatformAdminBootstrap"
```

The command prompts for email, full name and password (twice) with no echo. The password is never accepted as an argument, environment variable or file, and is never printed or logged — only a BCrypt hash is stored. If no interactive console is attached, the command refuses; `--allow-visible-input` exists only for exceptional cases and should not be used for real credentials.

## Get started on Windows
1. Extract `CPDTS_Project_Starter.zip` to a suitable folder, for example `Documents\Projects\CPDTS_Project_Starter`.
2. Open the extracted **CPDTS_Project_Starter** folder in OpenCode (or open a terminal in that folder and run `opencode` if it is installed and available on PATH).
3. Read `AGENTS.md` and `docs/`.
4. Paste the contents of `prompts/01_AUDIT_FIRST.md` into OpenCode.
5. Review the audit and resolve blocking decisions before authorizing scaffolding or database creation.

## Folder map
- `docs/`: six project specification documents.
- `frontend/`: reserved for React + TypeScript frontend.
- `backend/`: Spring Boot backend; Flyway migrations under `src/main/resources/db/migration` (V1–V6 applied).
- `database/`: database-related notes/scripts after approval.
- `tests/`: end-to-end and cross-module test assets.
- `scripts/`: setup and maintenance scripts after approval (`scripts/run_db.ps1` for local Flyway tasks).
- `prompts/`: reusable OpenCode prompts.
- `AGENTS.md`: baseline coding-agent instructions.

## Safety
Do not commit passwords, database credentials, `.env`, or customer payment data. The `*.example` files are templates only. Local database credentials belong only in the gitignored `.env.backend`, which is read through environment variables (`CPDTS_DB_URL`, `CPDTS_DB_USER`, `CPDTS_DB_PASSWORD`) and never printed. Automated tests only ever run against the disposable `cpdts_test` database.
