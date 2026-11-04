# CPDTS — Client Payment & Debt Tracking System

Starter workspace for JEIMAX INVESTMENTS COMPANY (`JMX`) and KAMWERUKA SECURITY & SAFETY ADMINISTRATOR CO. LTD. (`KAM`).

**Status:** Database foundation (Flyway V1–V8), authentication, company access, customer, invoice, payment and company-scoped reporting backends are implemented. The frontend provides sign-in, company selection, dashboard, customer, invoice and payment management.

## Current phase: payment management frontend

What is implemented and covered by automated tests (136 tests against the real `cpdts_test` database):

- **Session-based authentication (provisional decision):** login/logout via `POST/GET /api/v1/auth/*`; server-side HTTP sessions with BCrypt (cost 10) password hashes. JWT is NOT used; the token strategy in `docs/TECHNICAL_REQUIREMENTS.md` section 14 remains an open decision and sessions are the reversible default until that is approved.
- **UTF-8 password limit:** passwords are limited to 12–72 UTF-8 **bytes** (bcrypt's hard limit is byte-based). Over-long input is rejected up front — never silently truncated.
- **Session hygiene:** login rotates the session ID of any pre-login session (session-fixation protection); logout invalidates the session; user deactivation ends live sessions on the next request.
- **Permission matrix (migrations V6 + V8):** 8 permissions (`USER_MANAGE`, `CUSTOMER_MANAGE`, `INVOICE_MANAGE`, `PAYMENT_RECORD`, `PAYMENT_VERIFY`, `PAYMENT_REVERSE`, `RECORD_VIEW`, `REPORT_VIEW`) and 5 roles (`PLATFORM_ADMIN`, `COMPANY_ADMIN`, `FINANCE_OFFICER`, `PAYMENT_VERIFIER`, `VIEWER`). Authorization is permission-based (from `role_permissions`), not role-name-based. `PAYMENT_REVERSE` (V8) is granted **only** to `PLATFORM_ADMIN` and `COMPANY_ADMIN`, because whole-payment reversal is admin-only; platform admins additionally hold it through the code-level bypass set (`Permissions.ALL`, asserted to match the database catalogue exactly).
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
- **Company-scoped invoice management APIs** (PRD FR-04; reads require `RECORD_VIEW`, mutations require `INVOICE_MANAGE`, all permission-checked per target company, CSRF-protected, audit-logged in the same transaction):
  - `GET /api/v1/companies/{companyId}/invoices` — bounded pagination (`page` ≥ 0, 1 ≤ `size` ≤ 100, default 20) with optional `status` and `customerId` filters and `q` search over invoice number / description (ILIKE-escaped so `%`/`_`/`\` match literally), always scoped to the authorized company. Rows include the **derived** balance state: `outstandingAmount` (stored server-calculated total minus ACTIVE allocations of VERIFIED payments; zero unless the invoice is ISSUED) and `derivedStatus` (`DRAFT`/`ISSUED`/`VOIDED` are stored; `UNPAID`/`PARTIALLY_PAID`/`PAID`/`OVERDUE` are derived on read and never stored).
  - `POST /api/v1/companies/{companyId}/invoices` — creates a DRAFT for an **active customer of the same company** (foreign customer → 404, inactive → 409) with a **server-generated invoice number** (`JMX-INV-00001` style, from the company's `number_sequences` row of kind `INVOICE`, locked and incremented inside the creation transaction) and a **server-calculated total** (BigDecimal: each quantity × unit-price line rounded to 2 decimals HALF_UP — deliberately identical to the database's generated `line_total` column — then summed; currency is always TZS). Client-supplied numbers, totals, currency and status are never read.
  - `GET /api/v1/companies/{companyId}/invoices/{invoiceId}` — detail with line items; an invoice of another company is indistinguishable from a nonexistent one (404, no disclosure).
  - `PATCH .../{invoiceId}` — edits a **DRAFT only** (dates, description and/or items; a provided item list replaces the whole set and the total is recomputed). ISSUED and VOIDED invoices are immutable financial records (409). Edit, issue and void all lock the invoice row `FOR UPDATE` and re-check the lifecycle status after acquiring the lock, so edit/issue/void races serialize into exactly one winner.
  - `POST .../{invoiceId}/issue` — DRAFT → ISSUED; requires a positive total and re-checks that the customer is still active (drafts of since-deactivated customers simply stay drafts).
  - `POST .../{invoiceId}/void` — DRAFT/ISSUED → VOIDED with a **mandatory reason**; refused (409) while ACTIVE payment allocations exist — the row lock taken here is the coordination contract the future allocation phase must share, and migration V7 adds a database trigger as the backstop. VOIDED is terminal and excluded from collectible debt.
  - Rules: invoices are **never deleted** (no DELETE endpoint; the V2 database trigger also blocks it) — corrections are voids. Invoices of since-deactivated customers remain fully accessible (list, detail, draft edits where applicable, void). Historical invoice access is never lost. Audit events: `INVOICE_CREATED`, `INVOICE_UPDATED` (changed-fields diff), `INVOICE_ISSUED`, `INVOICE_VOIDED` (reason recorded) with the acting session user as actor; a simulated audit-write failure rolls back the whole mutation including the sequence increment (no number is ever burned).
- **Migration V7:** database-level backstop (`fn_check_allocation_invoice_issued` trigger) ensuring payment allocations can only ever target ISSUED invoices — tested on `cpdts_test` before being applied to `cpdts_dev`, in the V3 trigger style. The `PlatformAdminBootstrapper` schema-version guard was bumped to 7 accordingly (fail-closed by design).
- **Company-scoped payment management APIs** (PRD FR-05..FR-09; reads require `RECORD_VIEW`, recording and allocating require `PAYMENT_RECORD`, decisions require `PAYMENT_VERIFY`, whole-payment reversal requires `PAYMENT_REVERSE`; all permission-checked per target company, CSRF-protected, audit-logged in the same transaction as the change):
  - `POST /api/v1/companies/{companyId}/payments` — records a payment as `PENDING_VERIFICATION` for CASH / BANK_TRANSFER / MOBILE_MONEY. The **`Idempotency-Key` header is REQUIRED** (1–64 chars of `[A-Za-z0-9._-]`): a retry with the same key and an equivalent payload replays the stored entity with **200**, while the same key with a different payload is **409**. Payment number (`JMX-PAY-00001` style, from the company's `PAYMENT` sequence row, locked and incremented inside the creation transaction), recorder, company, TZS currency and status are all derived server-side — forged body fields are ignored. Amount must be positive, at most 2 decimal places and within `NUMERIC(14,2)` bounds (extra precision is **rejected, never rounded**); `receivedAt` accepts `yyyy-MM-dd` (start of day in Africa/Dar_es_Salaam) or an ISO-8601 instant, years 2000–2100.
  - `GET /api/v1/companies/{companyId}/payments` — bounded pagination (`page` ≥ 0, 1 ≤ `size` ≤ 100, default 20) with optional `status`, `customerId` filters and `q` search over payment number / reference / notes, always scoped to the authorized company; rows carry derived `allocatedAmount` (sum of the payment's ACTIVE allocations) and `availableCredit` (verified amount minus that sum, and **zero for every non-VERIFIED status** — pending, rejected and reversed payments contribute neither settled amounts nor credit).
  - `GET /api/v1/companies/{companyId}/payments/{paymentId}` — detail with append-only verification history and allocation rows; a payment of another company is indistinguishable from a nonexistent one (404, no disclosure).
  - `POST .../{paymentId}/verify` and `POST .../{paymentId}/reject` — only `PENDING_VERIFICATION` payments receive a decision (otherwise 409); rejection requires a **mandatory reason**. Status change, history row and `PAYMENT_VERIFIED`/`PAYMENT_REJECTED` audit event commit in ONE transaction. **The recorder can never decide their own payment — including platform admins** (service check first, V3 trigger `tg_payment_verifications_verifier` as the database backstop). `VERIFIED` and `REJECTED` are reached at most once.
  - `POST .../{paymentId}/allocations` — explicit allocation of a **VERIFIED** payment to **ISSUED** invoices of the **same customer and company** (violations are 409 for invoice state / 404 for a foreign or unknown invoice, never a composite-FK 500); partial and multi-invoice in one request, each invoice at most once per request, 1–100 lines. Never beyond the payment's available credit or the invoice's outstanding balance (both re-checked after the row locks). **`Idempotency-Key` is REQUIRED here too**: a replay returns 200 without allocating again, a key reused with a different payload is 409. There is **no automatic allocation** — unallocated verified funds are simply the customer's available credit.
  - `POST .../{paymentId}/reverse` — **whole-payment reversal, `PLATFORM_ADMIN` / `COMPANY_ADMIN` only** (V8), mandatory reason; flips the payment and ALL of its ACTIVE allocations to `REVERSED` in ONE transaction and restores the invoice balances, while preserving the payment, its decision history, its allocation rows and the audit trail. `REVERSED` and `REJECTED` are terminal: no re-decision, no further allocation, no second reversal. There is **no refund processing** and **no standalone allocation-reversal API**.
  - Rules: no `PUT`/`PATCH`/`DELETE` exists on any payment path (financial fields are never edited; the V3/V8 no-delete triggers remain the backstop), and payment recording deliberately does **not** require an ACTIVE customer (collecting from a since-deactivated customer stays legal). Duplicate payment references are permitted (the V3 index is deliberately non-unique — the duplicate-reference POLICY is still an open decision). Audit events: `PAYMENT_RECORDED`, `PAYMENT_VERIFIED`, `PAYMENT_REJECTED`, `PAYMENT_ALLOCATED`, `PAYMENT_REVERSED` (reason in `audit_logs.reason`), written with the session actor in the same transaction as the change — a simulated audit-write failure rolls back the whole mutation including the sequence increment (no payment number is ever burned) and the idempotency key row (a failed attempt never poisons a retry).
  - **Concurrency:** a documented row-lock ordering is used everywhere — the payment row is locked `FOR UPDATE` **first**, then the invoice rows in ascending-id order (the invoice void locks only the invoice; no operation locks an invoice before the payment), and every status/total/credit/outstanding check is **re-checked after acquiring the locks**. Verified by race tests: same-key creations elect exactly one payment, distinct keys yield contiguous payment numbers, verify-vs-reject elects one winner, two payments can never overpay one invoice, one payment can never be over-allocated, and reversal-vs-allocation / void-vs-allocation each end in exactly one consistent state.
- **Migration V8:** adds the eighth permission `PAYMENT_REVERSE` (granted only to `PLATFORM_ADMIN` and `COMPANY_ADMIN` via `role_permissions`) and the `idempotency_keys` table (`UNIQUE (company_id, scope, idempotency_key)` with a SHA-256 `request_hash` for key-reuse detection). Tested on `cpdts_test` before being applied to `cpdts_dev`; the `PlatformAdminBootstrapper` schema-version guard was bumped to 8 accordingly (fail-closed by design).

### Dashboard and reports

All routes below are GET under `/api/v1/companies/{companyId}` and require `REPORT_VIEW` for that company. There is no cross-company consolidated report. Page and size default to 0 and 20; size is limited to 1–100.

- `/dashboard`: current outstanding and overdue invoice counts and amounts, pending-verification count and amount, verified payment amount, and unallocated verified credit. Payment metrics include all currently matching records, regardless of received date.
- `/reports/debt/customers`: current outstanding and overdue invoice amounts and unallocated credit per customer, including inactive customers. Supports `page`, `size`, and `q` (customer name or code).
- `/reports/debt/invoices`: current positive outstanding issued invoices, ordered by due date and ID. Supports `page`, `size`, `q`, `customerId`, and `overdueOnly`.
- `/reports/payments`: payment activity by `received_at`, with `page`, `size`, `q`, `status`, `method`, `customerId`, `from`, and `to`. Dates are inclusive business dates, converted to a half-open instant range. Payment status and available credit are **current**, not historical as-of values.

Debt is gross, never netted against separately displayed credit. Only issued invoices carry debt, and only active allocations from verified payments settle it. Rejected, pending and reversed payments do not provide credit. Overdue means due date strictly before today's business date. `basis.observedAt` is the application observation instant, not a reconstruction of historical balances; `basis.businessDate` and date-only inputs use `Africa/Dar_es_Salaam`. Existing timestamps are not converted or rewritten. Report queries run in a read-only repeatable-read transaction for a consistent database snapshot.

Duplicate payment references remain permitted. Idempotency protects retries using the same key and payload; it cannot detect every independently entered duplicate payment using a different key.

What is NOT implemented yet (future approved phases):

- **Global management of existing users** — no global user directory, no global user status change, profile edit, password change/reset or account recovery (deliberate scope decision: company admins must not reach global account data; only the bootstrap writes `is_platform_admin`).
- **Session revocation lists, multi-session limits, account recovery, HTTPS-only cookie enforcement in production** — required before production per `docs/TECHNICAL_REQUIREMENTS.md`.
- **Refund processing, automatic allocation and a standalone allocation-reversal API** — deliberately out of scope: unallocated verified funds are customer credit, and only whole-payment reversal exists.
- Administration and report-list frontend screens.

## Running the frontend locally

Requires Node.js 22 and a running PostgreSQL database and backend. From the repository root in PowerShell, load your existing local credentials without displaying them, then start the backend:

```powershell
$kv = @{}
Get-Content .\.env.backend | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $p = $line -split '=', 2
        if ($p.Count -eq 2) { $kv[$p[0].Trim()] = $p[1].Trim() }
    }
}
$env:CPDTS_DB_URL = "jdbc:postgresql://$(if ($kv.DB_HOST) { $kv.DB_HOST } else { 'localhost' }):$(if ($kv.DB_PORT) { $kv.DB_PORT } else { '5432' })/$(if ($kv.DB_NAME) { $kv.DB_NAME } else { 'cpdts_dev' })"
$env:CPDTS_DB_USER = $kv.DB_USER
$env:CPDTS_DB_PASSWORD = $kv.DB_PASSWORD
mvn -f backend\pom.xml spring-boot:run
```

In a second terminal, from the repository root:

```powershell
cd frontend
npm ci --legacy-peer-deps
npm run dev
```

Open the URL printed by Vite (normally <http://localhost:5173>). Vite proxies `/api` to `http://localhost:8080`, preserving the existing session-cookie and CSRF checks. Sign in with an existing authorized account; no default credentials are supplied. The `--legacy-peer-deps` flag works around an npm 10 peer-resolution failure observed with the selected test dependencies. For verification run `npm run typecheck`, `npm test`, and `npm run build` in `frontend/`.

The dashboard uses current company balances and a date-filtered received-payment list (up to ten rows, with the total count); it does not claim historical balances or aggregate a partial payment page. Dates are Tanzania business dates. Amounts are parsed without binary floating-point conversion. The authentication response now includes server-resolved permissions per company; backend permission checks remain authoritative. Frontend tests mock API responses and are not live browser integration tests.

Customers can be searched and filtered by status with server-side pagination; details require `RECORD_VIEW`, while create, edit, activation and deactivation require `CUSTOMER_MANAGE`. The server assigns customer codes and company ownership. Changes use session CSRF protection and are not automatically retried. Switching companies or navigating away from edited forms prompts before discarding unsaved changes; a browser unload warning is also registered for dirty forms. There is no customer deletion.

Invoices provide company-scoped search, lifecycle and customer ID filters, pagination and details for `RECORD_VIEW`. `INVOICE_MANAGE` permits draft creation/editing, issuance and voiding with a required reason. Active customers are selected from the current company's paginated customer API. Item previews use integer arithmetic with per-line HALF_UP rounding; the backend remains authoritative. Date-only inputs remain dates, not UTC instants. For issued invoices, settled is derived exactly from the server total minus outstanding; it is not inferred for draft or voided invoices. Pending mutations are not automatically retried, and unsaved changes trigger a warning on navigation or company switch.

Payments use `RECORD_VIEW` for lists/details, `PAYMENT_RECORD` for recording and explicit allocations, `PAYMENT_VERIFY` for decisions by someone other than the recorder, and `PAYMENT_REVERSE` for whole-payment reversal. Received dates/times are Tanzania business dates/times. Payment creation and allocation retain their original payload and idempotency key in the current browser tab's `sessionStorage`, scoped by user, company and operation, until a successful response or explicit reconciliation. This allows a deliberate identical retry after an uncertain response, including when balances have since changed; no mutation is retried automatically. **These pending financial details remain in the tab until resolved or the tab is closed**; closing the tab loses recovery information. Check server-side payment and allocation history before clearing a retry key or entering a changed request. Backend authorization, verification separation, balances and concurrency checks remain authoritative.

Idempotency replays are also bound to the original actor on the backend: another user cannot replay that actor's key even if they have company permission and know the request. The original actor can still retry the identical request.

### Browser integration tests (local disposable database only)

Prerequisites: Node.js 22, Java/Maven, PostgreSQL and `psql`, Python with `bcrypt` (`python -m pip install bcrypt`), Microsoft Edge, and a migrated local `cpdts_test` database. `.env.backend` must contain the local DB credentials and `DB_TEST_NAME=cpdts_test`. From the repository root:

```powershell
cd frontend
npm ci --legacy-peer-deps
cd ..
python scripts\run_browser_tests.py
```

The runner accepts no database/port overrides. It connects only to `localhost:5432/cpdts_test`, checks `current_database()` before fixture writes, and starts a test-only backend on port 18081 with Flyway disabled and an independent startup database guard. A separate Vite server on port 15173 proxies only to that backend; the runner refuses occupied ports and requires its private server marker before browser actions. The normal development servers on ports 8080/5173 are not used. Fixtures use random credentials and unique test-only users; those users are deactivated after the run. Financial and append-only audit records are retained: **the runner does not truncate, reset or delete database history**. Test server logs and Playwright output are local test artifacts and must not be committed.

The guarded Playwright run completed two live browser tests: the desktop two-user create/issue/record/verify/allocate/retry/reverse flow (including cross-company isolation, logout and cross-user replay denial) and mobile customer, invoice and payment form interactions. These complement, rather than replace, the mocked frontend unit tests. For the non-destructive backend replay and guard regressions, after loading `CPDTS_DB_USER` and `CPDTS_DB_PASSWORD` as above, run:

```powershell
$env:CPDTS_DB_URL = 'jdbc:postgresql://localhost:5432/cpdts_test'
mvn -B -f backend\pom.xml '-Dtest=PaymentReplayActorTest,BrowserTestServerGuardTest,PasswordPolicyTest' test
```

The ordinary backend integration suites that truncate scratch financial tables must not be run when preserving existing test data.

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
- `frontend/`: React + TypeScript frontend (Vite).
- `backend/`: Spring Boot backend; Flyway migrations under `src/main/resources/db/migration` (V1–V8 applied).
- `database/`: database-related notes/scripts after approval.
- `tests/`: end-to-end and cross-module test assets.
- `scripts/`: setup and maintenance scripts after approval (`scripts/run_db.ps1` for local Flyway tasks).
- `prompts/`: reusable OpenCode prompts.
- `AGENTS.md`: baseline coding-agent instructions.

## Safety
Do not commit passwords, database credentials, `.env`, or customer payment data. The `*.example` files are templates only. Local database credentials belong only in the gitignored `.env.backend`, which is read through environment variables (`CPDTS_DB_URL`, `CPDTS_DB_USER`, `CPDTS_DB_PASSWORD`) and never printed. Automated tests only ever run against the disposable `cpdts_test` database.
