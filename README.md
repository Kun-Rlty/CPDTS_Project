# CPDTS — Client Payment & Debt Tracking System

Starter workspace for JEIMAX INVESTMENTS COMPANY (`JMX`) and KAMWERUKA SECURITY & SAFETY ADMINISTRATOR CO. LTD. (`KAM`).

**Status:** Database foundation (Flyway V1–V9), authentication, company access, customer, invoice, payment and company-scoped reporting backends are implemented. The frontend provides sign-in, company selection, dashboard, customer, invoice, payment, company membership and reports screens. A same-origin HTTPS reverse-proxy profile and a reproducible local HTTPS test setup exist; the current phase adds configurable temporary login-abuse throttling (per normalized account and trusted client address, HTTP 429 + `Retry-After`). This is still a local prototype: the proxy was verified locally, not deployed, the throttle counters are in-memory and per instance, and this is not an online-pilot or production readiness approval.

## Current phase: configurable login-abuse throttling (temporary cooldown)

**Existing local databases remain owner-managed.** Read-only inspection on 2026-11-05 found `cpdts_dev` at V9 and connected with the restricted `cpdts_app` runtime login. The retained `cpdts_test` database, its data and its roles were not modified or used by the new browser runner. The runtime still rejects a superuser/owner role and requires successful Flyway versions 1–9; do not start it against another existing database until an owner-approved upgrade and independent grant review are complete. The existing `cpdts_dev` platform administrator must be preserved; do not rerun the one-time bootstrap.

What is implemented and covered by automated tests (backend endpoint suites, frontend tests and guarded browser tests):

- **Session-based authentication (provisional decision):** login/logout via `POST/GET /api/v1/auth/*`; server-side HTTP sessions with BCrypt (cost 10) password hashes. JWT is NOT used; the token strategy in `docs/TECHNICAL_REQUIREMENTS.md` section 14 remains an open decision and sessions are the reversible default until that is approved.
- **Password limit:** passwords require at least 12 characters and at most 72 UTF-8 **bytes** (bcrypt's hard limit is byte-based). Over-long input is rejected up front — never silently truncated.
- **Session hygiene:** login rotates the session ID of any pre-login session (session-fixation protection); logout invalidates the session; user deactivation ends live sessions on the next request.
- **Login abuse throttling (temporary cooldown):** failed sign-ins are counted per normalized account and per trusted client address (connection remote address only — spoofed forwarded headers are rejected), with configurable prototype defaults (5 account failures / 20 address failures / 300 s cooldown / 10 000 keys). Repeated failures receive HTTP 429 + `Retry-After` instead of the unchanged 401 response; cooldowns expire, a successful sign-in clears them, and no configuration can permanently lock an account. See "Login abuse throttling" below.
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

### Login abuse throttling (temporary cooldown, prototype defaults)

Failed sign-ins are counted against two keys: the normalized account (trimmed, lowercased email — the same identity helper used everywhere) and the trusted client address. The address is the connection's remote address only: Tomcat's `RemoteIpValve` rewrites it solely for the loopback proxy, and `TrustedProxyHeadersFilter` rejects forwarded headers without the proxy token, so a client cannot forge its throttle key.

| Property | Meaning | Default |
| --- | --- | --- |
| `cpdts.login-throttle.account-failures` | Failures per account before a cooldown | 5 |
| `cpdts.login-throttle.ip-failures` | Failures per client address before a cooldown | 20 |
| `cpdts.login-throttle.cooldown-seconds` | Cooldown length and stale-entry expiry | 300 |
| `cpdts.login-throttle.max-entries` | Bounded key capacity (fail-open) | 10000 |

Override per environment with `SPRING_APPLICATION_JSON` or `-D` flags (for example `-Dcpdts.login-throttle.account-failures=8`); the dashed property names are deliberately not mapped to guessed relaxed-binding environment-variable names.

- The throttle check runs BEFORE any account or credential lookup: a cooldown returns the same JSON `429 {"error":"login_throttled",...}` with a `Retry-After` delta-seconds header (rounded up to whole seconds, minimum 1) for unknown and existing accounts alike, and below the threshold the original identical `401 invalid_credentials` response is unchanged — no account-enumeration signal either way.
- An attempt rejected during an active cooldown is not recorded, so hammering cannot extend the window; failures age out after `cooldown-seconds` and a successful sign-in clears both keys. This is a temporary cooldown only — there is no permanent lockout and no configuration that lets one attacker permanently block an account.
- The sign-in screen explains the pause ("Sign-in is temporarily paused … No account is permanently locked."), counts down from `Retry-After`, disables the button until it expires, clears the password and never resubmits it automatically; retry after the cooldown is manual.
- Logging stays minimal: a rate-limited WARN when a cooldown starts records a SHA-256 12-hex fingerprint of the account (never the address itself), the client address and the cooldown length; individual failures and rejected attempts are DEBUG; reaching the storage cap logs a rate-limited WARN and fails open (new keys are not tracked instead of growing memory without limit). No passwords, tokens, session IDs or request bodies are ever logged.
- LIMITATION: the counters are in-memory and per JVM instance — they are not shared between replicas and reset on restart. A multi-instance deployment needs a shared store, which is out of scope for the prototype. Backend test properties raise the thresholds so the shared-IP `cpdts_test` suites cannot trip the limiter; the guarded browser server runs the documented defaults above.

What is NOT implemented yet (future approved phases):

- **Global management of existing users** — no global user directory, no global user status change, profile edit, password change/reset or account recovery (deliberate scope decision: company admins must not reach global account data; only the bootstrap writes `is_platform_admin`).
- **Session revocation lists, multi-session limits and account recovery** — required before production per `docs/TECHNICAL_REQUIREMENTS.md`. Secure-cookie and proxy settings now exist, but HTTPS deployment itself is an operator step (see "HTTPS and same-origin routing" below).
- **Refund processing, automatic allocation and a standalone allocation-reversal API** — deliberately out of scope: unallocated verified funds are customer credit, and only whole-payment reversal exists.
- Company settings and audit-log frontend screens.

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

Users & Access requires `USER_MANAGE` in the selected company. It lists the existing API's unpaginated membership results, separately showing global account status and company-membership status. Managers can create users and change membership roles/status; only platform admins can attach an existing account. The UI offers the four seeded assignable roles, never `PLATFORM_ADMIN`, and has no global-profile, global-status or credential-edit controls. Password input is not stored in browser storage or persisted form state and is cleared on submission or form exit. After a change to one's own membership, the UI refreshes session permissions and fails closed while access is refreshed.

Reports requires `REPORT_VIEW` in the selected company. Customer debt, outstanding/overdue invoices and the payment register use server-side pagination and the existing API filters. Debt and verified unallocated credit are separate current balances, not historical balances. Payment date filters apply to received dates in Tanzania; payment statuses shown are current, not historical. No report-wide totals are inferred from a single page. Exports are not included.

Idempotency replays are also bound to the original actor on the backend: another user cannot replay that actor's key even if they have company permission and know the request. The original actor can still retry the identical request.

### Browser integration tests (fresh disposable least-privilege database)

Prerequisites: Node.js 22, Java/Maven, PostgreSQL 18 binaries (`initdb`, `pg_ctl`, `psql`; override the default `C:\Program Files\PostgreSQL\18\bin` with `CPDTS_PG_BIN` only if needed), Python with `bcrypt` (`python -m pip install bcrypt`), OpenSSL (for the local HTTPS certificate), and Microsoft Edge. A migrated `cpdts_test` and database credentials in `.env.backend` are **not** required. From the repository root:

```powershell
cd frontend
npm ci --legacy-peer-deps
cd ..
python scripts\run_browser_tests.py
```

The runner accepts no database/server/port overrides. It creates a temporary PostgreSQL cluster on `127.0.0.1:55432`, separate from the owner's existing cluster at port 5432, and removes it after the run. It generates three process-local logins: a one-time cluster administrator, a migration owner, and a restricted `NOINHERIT` runtime login with no role memberships. Flyway V1–V9 is applied and validated with the owner; `scripts/grant_runtime.sql` is then applied by that owner. The existing `tests/database/least_privilege_checks.sql` runs as administrator (using `SET ROLE` for the runtime checks), and `tests/database/runtime_denial_checks.sql` connects directly as the runtime login.

Only after those checks pass does the runner seed browser fixtures with the owner and start the test backend on port 18081 using **only** the runtime role in `CPDTS_DB_USER`/`CPDTS_DB_PASSWORD`. Migration credentials are removed from the backend and UI process environments. The backend's normal `RuntimeDatabaseGuard` and the test-server disposable-database guard both remain active; no startup guard is bypassed. A separate Vite server on port 15173 proxies only to that backend. The runner refuses occupied ports and requires its private server marker before browser actions. The normal development servers on ports 8080/5173 are not used.

Fixtures use random credentials and unique test-only users; those users are deactivated before the temporary cluster is removed. Financial and append-only audit records are never truncated or deleted by the runner. Test logs are retained under `%TEMP%\opencode\cpdts-lp-browser-*\logs\`; they are local artifacts and must not be committed. The disposable database itself is removed, so no test data or generated roles remain in the owner's cluster.

The guarded Playwright suite covers login/logout **and the temporary login throttle** (identical 401s below the threshold, then JSON 429 with `Retry-After`, the UI countdown and no automatic password resubmission), user creation and membership role/status changes, platform-admin attachment, immediate self-access loss, customer creation **and edit**, draft invoice creation **and edit**, issuance, separate-user payment verification, allocation with uncertain-response replay, reversal, reports filters/pagination/isolation, cross-company denials, and desktop/mobile layout. These tests complement, rather than replace, mocked frontend unit tests.

Database-independent backend regressions can be run with:

```powershell
mvn -B -f backend\pom.xml "-Dtest=BrowserTestServerGuardTest,RuntimeDatabaseGuardTest,PasswordPolicyTest,TrustedProxyHeadersFilterTest,LoginThrottleTest,AuthServiceLoginThrottleTest,LoginThrottleEndpointTest,ApiExceptionHandlerTest" test
```

The older owner-based `UserAccessIsolationTest` and `PaymentReplayActorTest` are hard-coded to the retained `cpdts_test` and require owner writes, so they are not part of this disposable runner. The ordinary backend integration suites that truncate scratch financial tables must never be run against retained databases.

### HTTPS and same-origin routing (local proof, not deployment)

The proxy contract is defined by `deploy/nginx/cpdts.conf`, a template for the hosting step, and mirrored by `scripts/https_proxy.mjs` for local verification. Both serve the production SPA build and the API under one HTTPS origin:

- `/api/` and `/actuator/health` are proxied to the backend; unknown API paths return a JSON 404 and are never replaced by `index.html`.
- Missing `/assets/*` files and other file-like paths return 404; remaining paths fall back to `index.html` so SPA deep links survive a refresh.
- Client-supplied `Forwarded`, `X-Forwarded-*` and `X-Real-IP` headers are removed and replaced by the proxy. The proxy also adds `X-CPDTS-Proxy-Token`, and the backend rejects any request that carries forwarded headers without the matching token (`400 untrusted_forwarded_headers`).
- HSTS, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer` are added. Only `/actuator/health` is exposed; other actuator paths return 404.

Backend deployment profile (`SPRING_PROFILES_ACTIVE=proxy`, `application-proxy.properties`):

- binds to `127.0.0.1` only;
- trusts forwarded headers only from loopback (`server.tomcat.remoteip.internal-proxies=127.0.0.1/32,::1/128`);
- sets the session cookie to `__Host-CPDTS_SESSION` with `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/` and no `Domain`;
- requires `CPDTS_PROXY_TOKEN`. Local HTTP development keeps the existing non-secure cookie and does not process forwarded headers.

The guarded runner performs the local HTTPS proof automatically after the HTTP suite: it builds the frontend, generates a short-lived test CA and server certificate under the disposable run directory, starts a second backend with the `proxy` profile on `127.0.0.1:18082`, starts the local HTTPS proxy on `127.0.0.1:18443`, asserts that both listeners are loopback-only, and runs `frontend/e2e/https.pw.ts` (2 tests). The certificate chain is checked with `openssl verify`; because the CA is self-signed and local, Playwright uses `ignoreHTTPSErrors` for this origin only. Generated keys and certificates are deleted when the run finishes and are ignored by Git.

To inspect the same origin manually, generate certificates into a directory outside the repository and trust the CA explicitly:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\create_local_https_cert.ps1 -OutDir "$env:TEMP\opencode\cpdts-https-local"
Import-Certificate -FilePath "$env:TEMP\opencode\cpdts-https-local\ca.crt" -CertStoreLocation 'Cert:\CurrentUser\Root'
```

Import only `ca.crt`, restart the browser, and remove the CA from `Cert:\CurrentUser\Root` when finished. Never commit `ca.key`, `server.key` or generated certificates. Do not install the test CA on a shared or production machine. To browse the built SPA through the local proxy, start `scripts/https_proxy.mjs` with `CPDTS_TLS_CERT`, `CPDTS_TLS_KEY`, `CPDTS_PROXY_TOKEN` and `CPDTS_UPSTREAM_PORT` set for a matching proxy-profile backend, then open `https://localhost:18443`.

Operator steps for an actual host (not performed here):

1. Build the frontend with `npm run build` and copy `frontend/dist` to the web root referenced by the template (`/var/www/cpdts`).
2. Obtain a certificate for the real hostname from the approved CA and place `fullchain.pem`/`privkey.pem` at the paths in the template, restricted to the nginx service account. Do not use the local test CA.
3. Generate a random proxy token, store it in `/etc/cpdts/proxy-token.conf` with mode `0600` (see `deploy/nginx/proxy-token.conf.example`), and set the same value as `CPDTS_PROXY_TOKEN` for the backend.
4. Start the backend with `SPRING_PROFILES_ACTIVE=proxy` on loopback only. Keep port 8080 closed to the network and allow only the proxy to reach it.
5. Replace the `server_name`/certificate-path placeholders, run `nginx -t`, reload nginx, and verify HTTPS redirect, HSTS, cookie flags, deep-link refresh, API 404 isolation and login/logout on the real host.
6. Keep `.env.backend`, private keys and the real proxy token out of source control.

Limitations: nginx is not installed on the development machine, so `deploy/nginx/cpdts.conf` is provided for the hosting step and was reviewed structurally but not executed. The verified artifact is the equivalent local Node HTTPS proxy. Trusting the local CA is a manual development convenience, not production certificate management. The local proof establishes the application/proxy contract only; it does not prove firewall, DNS, TLS-termination, certificate-renewal or hosting configuration. No deployment was performed, and no recovery or new modules were added in that phase (temporary login throttling was added afterwards — see "Login abuse throttling" above).

### Prototype readiness and acceptance

| Status | Scope |
| --- | --- |
| Complete for prototype | Session login/logout, company-scoped customers, invoices, manually verified payments and allocations/reversals, memberships, dashboard and supported reports. |
| Incomplete | Online security and operating controls below; representative user acceptance; customer statement; company settings and audit-log UI from the broader draft requirements. |
| Intentionally deferred | Exports, evidence uploads, recovery/global-user management, payment gateways, refunds and automatic allocations. |

Before an online pilot: independently review and provision least-privilege runtime and separate migration credentials; deploy the supplied proxy template on the real host with a publicly trusted certificate, a private backend listener and the shared proxy token, then re-verify cookie/CSRF behavior and same-origin SPA/API routing there; review the implemented temporary login throttling (prototype in-memory counters — choose thresholds for the real environment and provide a shared store before running more than one instance); review sanitized error logging, audit privileged reads, monitoring and incident response; approve backup encryption, retention, RPO/RTO and recovery ownership; and complete UAT. Runtime Flyway is disabled and a datasource username must be supplied explicitly; existing local credentials have not been changed. The local HTTPS proof does not configure or validate hosting, DNS, firewalls or certificate renewal. Sessions are provisional; recovery and revocation policies remain unresolved. API catch-all responses are generic, but the exception handler logs full exceptions and stack traces, which require sensitive-data review. Do not use real customer data for prototype checks.

### Separate database credentials (new dedicated database)

The following steps are for a **new, dedicated, operator-approved database**, not an instruction to alter `cpdts_dev` or retained `cpdts_test`. Back up and review ownership, existing role memberships, PUBLIC grants and application dependencies before separately planning conversion of any existing database. Never paste real passwords into SQL, command-line arguments, logs or chat; use a protected PostgreSQL password file or private prompts. Names below are placeholders, not pre-created accounts.

1. A PostgreSQL administrator creates two dedicated login roles: `<migration_owner>` and `<runtime_login>`. Both must be `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT`, with no role memberships. Use `psql`'s `\password <role>` interactively for each password. Create a new database owned by `<migration_owner>` using a separately authorized administrator. Do not grant the runtime role membership in the migration role.
2. Database administrators must inventory the target database's `PUBLIC` and inherited grants. On a dedicated database, `REVOKE TEMPORARY ON DATABASE <new_database> FROM PUBLIC` removes a default capability for **every** role in that database, so assess the impact and explicitly grant TEMPORARY only to other roles that need it. Verify that `PUBLIC` and role memberships do not convey schema CREATE, dangerous table/column privileges, ownership or DDL to runtime. This repository does not change unrelated roles or their grants automatically.
3. Set `CPDTS_DB_URL` to the new database JDBC URL and set `CPDTS_MIGRATION_USER` and `CPDTS_MIGRATION_PASSWORD` privately for the migration process. Run `mvn -f backend\pom.xml flyway:migrate`, then `mvn -f backend\pom.xml flyway:validate`. Migration credentials are separate from `CPDTS_DB_USER`/`CPDTS_DB_PASSWORD`. Review Flyway output without publishing credentials. Normal startup does not migrate or perform Flyway checksum validation; the operator must do both before applying grants and starting the application.
4. Connect to the intended database as its object-owning migration role and run `psql -h <host> -U <migration_owner> -d <new_database> -v runtime_role=<runtime_login> -f scripts/grant_runtime.sql`. The script refuses several unsafe pre-existing role, ownership, schema and PUBLIC-grant conditions; it is not a complete privilege audit. Review actual effective grants, column grants, object/function owners, default privileges and memberships after every migration. Identity-generated inserts were verified without granting runtime sequence privileges. Trigger functions use invoker security, not `SECURITY DEFINER`.
5. For a first installation, run the one-time platform-admin bootstrap separately with the migration/administrative connection in `CPDTS_DB_USER`/`CPDTS_DB_PASSWORD`; do not give that connection to the running application. The bootstrap reads the administrator password interactively and checks schema version 9. Afterwards clear administrative environment variables and start the application in a new process using only the runtime connection in `CPDTS_DB_USER` and `CPDTS_DB_PASSWORD`. Do not overwrite `.env.backend` automatically.
6. Verify the effective runtime role cannot create or alter objects, create temporary tables, truncate, modify audit/verification history or set `is_platform_admin`; verify normal workflows and both company boundaries. Runtime grants do not replace application authorization and cannot protect against an object owner or privileged database administrator. Audit INSERT and permitted application updates remain available to the service account. Reapply/review the runtime grant profile following approved migrations; future objects are not automatically granted.

At startup the runtime uses its own connection to read `flyway_schema_history` and requires successful versions 1–9, with no failed or unknown versioned migrations. It rejects a superuser or a role that owns, or can assume an owner of, the database, `public` schema or its relations, functions or types. This targeted guard is **not** checksum validation or a complete effective-privilege audit. Grant read-only access to Flyway history by running the grant script after migration and validation; do not pass migration credentials to the running application. The browser runner creates a fresh disposable database and starts the application with its restricted runtime role; it does not use retained `cpdts_test`. Legacy owner-based backend integration fixtures replace the runtime guard in their own test context; the guard itself is verified separately.

For step 1, in a private administrator `psql` session replace the placeholders with reviewed, simple lowercase identifiers (do not use real credentials in these commands):

```sql
CREATE ROLE <migration_owner> LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
CREATE ROLE <runtime_login> LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
\password <migration_owner>
\password <runtime_login>
CREATE DATABASE <new_database> OWNER <migration_owner> TEMPLATE template0;
REVOKE TEMPORARY ON DATABASE <new_database> FROM PUBLIC;
```

The TEMPORARY revoke has the database-wide effect described in step 2. For step 3, in a private PowerShell session, set the JDBC URL and migration username as above, and enter the password without echo:

```powershell
$env:CPDTS_DB_URL = 'jdbc:postgresql://<host>:5432/<new_database>'
$env:CPDTS_MIGRATION_USER = '<migration_owner>'
$secure = Read-Host 'Migration database password' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $env:CPDTS_MIGRATION_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
mvn -f backend\pom.xml flyway:migrate
if ($LASTEXITCODE -ne 0) { throw 'Migration failed; do not start the application.' }
mvn -f backend\pom.xml flyway:validate
if ($LASTEXITCODE -ne 0) { throw 'Validation failed; do not start the application.' }
Remove-Item Env:CPDTS_MIGRATION_PASSWORD
```

The process environment necessarily holds the credential while Maven runs; use a private terminal and clear it afterwards. For application startup use a separate private process, set `CPDTS_DB_USER` to `<runtime_login>`, enter `CPDTS_DB_PASSWORD` using the same no-echo pattern, set `CPDTS_DB_URL` to the reviewed database URL, and start the backend. Do not use these examples to change existing environments without an independently reviewed migration plan.

Disposable browser verification on 2026-11-05 used a fresh temporary PostgreSQL 18.4 cluster, separate one-time administrator, migration-owner and restricted runtime logins, Flyway V1–V9 migrate/validate, `scripts/grant_runtime.sql`, `tests/database/least_privilege_checks.sql` and `tests/database/runtime_denial_checks.sql`. The direct runtime checks recorded denials for `is_platform_admin` INSERT/UPDATE, audit and verification history UPDATE/DELETE, idempotency mutation, invoice-item update, customer company reassignment, company/user mutation, public and temporary DDL, `ALTER`/`DROP`, role creation and TRUNCATE of application tables. The backend then started on port 18081 with the runtime login and the normal `RuntimeDatabaseGuard` active. The guarded Playwright suite passed 5/5 tests, covering login, user creation and membership changes, platform-admin attachment and self-access loss, customer creation and edit, draft invoice creation and edit, issuance, separate-user verification, allocation with uncertain-response replay, reversal, reports and cross-company denials. Database-independent backend tests passed 14/14; frontend typecheck, 59 unit tests and the production build passed.

HTTPS and routing verification (2026-11-05, same disposable database): the runner built the production SPA, started a second backend with the `proxy` profile on `127.0.0.1:18082`, and started the local HTTPS proxy on `127.0.0.1:18443`; both listeners were asserted to be loopback-only. The generated certificate chain verified with `openssl verify`, and the HTTPS Playwright suite passed 2/2. It covered login, refresh/session restore, logout, company switching, a protected mutation with valid CSRF, rejection without CSRF with no record created, cookie flags (`__Host-CPDTS_SESSION`, `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, no `Domain`), SPA deep-link refresh, JSON API 404 isolation, missing-asset 404, HSTS, direct rejection of spoofed forwarded headers, and acceptance of proxy-sanitized headers. The first HTTPS attempt exposed an unknown-API-path 500; `ApiExceptionHandler` now returns a JSON 404, with a regression test, and the full run was repeated. Selected backend regressions passed 23/23 (`ApiExceptionHandlerTest`, `TrustedProxyHeadersFilterTest`, `BrowserTestServerGuardTest`, `RuntimeDatabaseGuardTest`, `PasswordPolicyTest`); frontend typecheck, 59 unit tests and the production build passed again.

Login-throttling verification (2026-11-11, same guarded procedure): backend throttle tests passed 24/24 (`LoginThrottleTest` 10, `AuthServiceLoginThrottleTest` 6, `LoginThrottleEndpointTest` 5, `ApiExceptionHandlerTest` 3) covering thresholds, controllable-clock cooldown expiry, normalized-email keying, unknown/existing-account parity, independent account/address limits, concurrency, bounded storage with stale-entry sweeping, trusted-proxy address handling and successful-login clearing. The documented database-independent regression command passed 46/46. Frontend typecheck, 64 unit tests and the production build passed. The guarded runner passed 6/6 HTTP tests (including the new end-to-end throttle test: identical 401s, then 429 + `Retry-After`, the UI countdown and no automatic resubmission) and 2/2 HTTPS tests against a fresh disposable least-privilege database; least-privilege SQL checks passed and the disposable cluster was removed. `PlatformAdminBootstrapperTest` fails identically at baseline `e807324` in this environment (the local `.env.backend` runtime credential lacks Flyway permissions) — a pre-existing limitation, not a regression; owner-based `cpdts_test` suites were not run.

An initial runner attempt stopped on a SQL guard syntax typo in the seed transaction before the application started; it was corrected and the full checks were rerun. Verification limits: this is a temporary cluster using the installed PostgreSQL 18 binaries, not a conversion or modification of `cpdts_dev` or retained `cpdts_test`; owner-based backend test classes hard-coded to `cpdts_test` were not run; the nginx template was not executed because nginx is not installed; the HTTPS proof used a local self-signed test CA and the equivalent Node proxy; and these local checks do not establish deployment security, hosting configuration or replace an operator's complete effective-grant audit.

Existing-database migration requires an operator-reviewed ownership plan: the inspected `cpdts_dev` Flyway history is owned by `postgres`, and V9 creates a trigger on an existing table. A newly created non-owner migration login cannot apply it without the required object ownership/authority. Do not assume the new-database instructions above transfer ownership. An authorized administrator may perform the controlled migration using the existing owner credential in the separate migration process, then apply runtime grants; any later ownership transfer needs its own reviewed plan. Never grant owner or migration-role membership to runtime.

Manual acceptance on a dedicated test environment:
1. Sign in and out, switch authorized companies, and confirm a company-only user cannot access the other company by URL or API.
2. Create a test customer and invoice, issue it, record a payment, and verify that pending payments do not change debt.
3. Sign in as a different authorized verifier, verify and allocate partially; check balances and then reverse with a reason and inspect preserved history.
4. Change a test membership and confirm immediate self-access loss where appropriate; check the separate account and membership statuses.
5. Filter and paginate each report, confirm current debt and verified credit are separate, and check desktop and mobile layouts.

### Backup and restore (operator procedure)

Backups contain confidential customer, financial, audit and password-hash data. Store encrypted outside this repository with restricted access; never commit dumps, logs or credentials. The following is a **logical backup and restore into a new isolated empty database**, not a command to overwrite a running database. Use a PostgreSQL client compatible with the server and an appropriately authorized backup/restore operator. Supply credentials privately (for example via a protected `PGPASSFILE`), not in commands, source control or console output.

1. Identify and independently verify the source host, port and database. Coordinate a consistent backup and protect the archive and keys. PostgreSQL `pg_dump` creates a consistent database snapshot, but does not back up cluster roles or external configuration; document and restore those separately as needed.
2. Back up using explicit placeholders replaced after verification: `pg_dump -h <host> -p <port> -U <backup-role> -Fc --no-owner --no-acl -f <secure-outside-repository-path> <source-database>`.
3. On an isolated test server, create a **new, empty, explicitly named disposable** database using `createdb -h <test-host> -U <restore-role> -T template0 <new-disposable-database>`. Verify host and `SELECT current_database()` before proceeding. Never restore over `cpdts_dev`, retained `cpdts_test`, or a live database.
4. Run `pg_restore -h <test-host> -U <restore-role> -d <new-disposable-database> --no-owner --no-acl --exit-on-error <secure-outside-repository-path>`. Validate Flyway history, representative customer/invoice/payment and audit records, constraints, triggers, role grants and application access; reconcile key balances against the source snapshot. These flags do not restore ownership or privileges; configure and verify them separately.
5. Record results and elapsed time, protect or securely dispose of the backup and test copy according to the approved policy. A real recovery must use an approved, separately reviewed runbook; RPO, RTO, retention, offsite storage, encryption and recovery ownership are not yet decided.

Local drill (2026-11-04): a fresh disposable database was migrated and populated with synthetic test data; a custom-format `pg_dump` was restored to a second newly created disposable database. Counts matched for 8 successful migrations, 265 audit rows, one customer, one invoice, one payment and 9 non-internal triggers. This was not a production-data restore, balance reconciliation, privilege validation, or an RPO/RTO demonstration. The isolated test archive is outside the repository. The broader destructive tests must never target retained databases: their test properties and base-class guard are hard-coded to `cpdts_test`, so a disposable-only isolated copy and explicit name guard were used for this review.

## Creating the first platform administrator

Run from an **interactive terminal** (required so the password can be read without echo). This is an administrative operation: use the separate migration/administrative credential, never the restricted application runtime credential. The following example expects the operator to have privately supplied `DB_MIGRATION_USER` and `DB_MIGRATION_PASSWORD` in the local configuration; it does not alter that file.

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
$env:CPDTS_DB_USER = $kv.DB_MIGRATION_USER
$env:CPDTS_DB_PASSWORD = $kv.DB_MIGRATION_PASSWORD

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
- `backend/`: Spring Boot backend; Flyway migrations under `src/main/resources/db/migration` (V1–V9 applied).
- `deploy/`: reverse-proxy deployment template and token placeholder (not executed locally; no secrets).
- `database/`: database-related notes/scripts after approval.
- `tests/`: end-to-end and cross-module test assets.
- `scripts/`: guarded browser runner, local HTTPS proxy, local certificate generator and database setup helpers (`scripts/run_db.ps1` for local Flyway tasks).
- `prompts/`: reusable OpenCode prompts.
- `AGENTS.md`: baseline coding-agent instructions.

## Safety
Do not commit passwords, database credentials, `.env`, customer payment data, private keys, certificates or the real proxy boundary token. The `*.example` files are templates only. Local database credentials belong only in the gitignored `.env.backend`, which is read through environment variables (`CPDTS_DB_URL`, `CPDTS_DB_USER`, `CPDTS_DB_PASSWORD`) and never printed. Automated browser tests run only against disposable `cpdts_lp_*` databases created and removed by the guarded runner; they never target `cpdts_dev` or the retained `cpdts_test`. Local HTTPS certificates are generated under the disposable run directory (or another directory outside the repository) and are never committed.
