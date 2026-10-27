# Technical Requirements Document
## Customer Payment & Debt Tracking System (CPDTS)

- **Status:** Proposed baseline; verify against the actual repository before implementation
- **Version:** 1.0
- **Last updated:** 27 October 2026

## 1. Purpose

This document defines the technical constraints and quality requirements for CPDTS. The proposed stack is a starting decision, not evidence that the repository or developer machine is already configured.

## 2. Proposed technology baseline

| Layer | Proposed technology | Notes |
|---|---|---|
| Frontend | React + TypeScript | Responsive web application |
| Backend | Java + Spring Boot REST API | Modular monolith for first release |
| Database | PostgreSQL | Relational source of truth |
| Schema migration | Flyway | Versioned SQL migrations |
| API contract | OpenAPI | Document endpoints and schemas |
| Testing | JUnit 5, Spring test tools, frontend test tooling | Select exact libraries after repository audit |
| Version control | Git | Feature branches and reviewed commits |
| Local development | Native tools or an agreed container setup | Do not require Docker unless approved |

Use compatible, supported versions at the time of setup. Before installing dependencies, inspect the existing project and record actual versions. Do not upgrade or replace working tooling without explaining the reason and receiving approval.

## 3. Architecture requirements

- Use a **modular monolith** for the initial release: one backend application divided into clear modules.
- Keep frontend, backend, database schema/migrations, and documentation responsibilities separate.
- Backend modules may include authentication/authorization, companies/memberships, customers, invoices, payments/verification, reporting, and audit.
- Expose a versioned REST API, for example `/api/v1/...`.
- Use DTOs/request-response models rather than exposing persistence entities directly.
- Put business rules in backend services, not only in frontend code.
- Use database transactions for operations that change payment state, allocations, and financial balances.
- Keep secrets and environment-specific configuration out of Git.

## 4. Security requirements

### Authentication
- Hash passwords using a modern adaptive algorithm supported by the chosen security library.
- Never store plaintext passwords or commit credentials.
- Define access-token/session expiry, refresh/revocation strategy, sign-out behavior, and account recovery before production.
- Use TLS/HTTPS in deployed environments.

### Authorization
- Enforce permission checks on every protected backend operation.
- Enforce company scope on every business-data query and mutation.
- Do not trust a `companyId` supplied by the browser without checking membership/privilege.
- Prevent insecure direct object reference (IDOR) attacks by checking authorization for the specific record.
- Payment verification must check both verifier permission and `verifier_id != recorder_id`.
- Privileged platform-administrator access should be logged.
- Avoid returning records from another company in search, reports, counts, errors, or autocomplete results.

### Input and file handling
- Validate all input on the backend.
- Apply length, range, enum, date, amount, and relationship validation.
- If evidence files are supported, enforce allowed file types, size limits, safe storage, and non-public access. Do not trust file extensions alone.
- Never execute uploaded files.
- Avoid placing passwords, tokens, full payment evidence, or unnecessary personal data in application logs.

### Audit and integrity
- Audit important financial and permission changes.
- Use least-privilege database credentials for runtime access.
- Use a separate migration credential where practical.
- Protect audit history from ordinary application users.
- Do not implement financial deletion as a normal CRUD delete.

## 5. Database requirements

- Use PostgreSQL as the source of truth.
- Use Flyway versioned migrations; never rely on manually editing production tables.
- Use `NUMERIC(14,2)` or another explicitly approved fixed-precision numeric type for TZS amounts.
- Use foreign keys, `NOT NULL`, `UNIQUE`, `CHECK`, and indexes where appropriate.
- Include company scope on tenant-owned business tables and enforce same-company relationships.
- Prefer composite uniqueness/foreign-key patterns where needed to prevent cross-company relationships.
- Use timezone-aware timestamps (`TIMESTAMPTZ`) for event times; define business-date rules clearly.
- Use generated identity keys or UUIDs consistently; the schema decision must document the choice.
- Never seed a real administrator with a known plaintext/default password.
- Migrations must be repeatable in a fresh local database and tested in CI or an equivalent documented process.
- Backups and restore procedures must be tested before production.

## 6. Financial correctness requirements

- Store amounts as fixed-precision numeric values, not `float`/`double`.
- Define rounding and decimal precision rules.
- Invoice totals are derived from line items in trusted backend logic.
- Payment verification and allocation must be atomic where their combined outcome affects balances.
- Prevent allocations exceeding the available verified payment amount.
- Prevent over-allocation of an invoice unless an approved credit policy explicitly allows it.
- Protect against race conditions using transactions and suitable locking/constraints.
- Treat balances as derived data unless a rigorously maintained ledger/materialized balance design is approved.
- Reversal behavior must preserve history and correctly reverse financial effects.

## 7. API requirements

- Use consistent response/error formats and meaningful HTTP status codes.
- Validate request DTOs.
- Return only fields the caller is authorized to see.
- Support pagination, sorting, and bounded filtering for list endpoints.
- Use server-side filtering by company scope before pagination and aggregation.
- Document authentication, permissions, validation errors, and examples in OpenAPI.
- Avoid exposing internal stack traces or database details to clients.
- Consider idempotency or duplicate-submission protection for sensitive payment operations.

## 8. Frontend requirements

- Use TypeScript strictness appropriate to the chosen project configuration.
- Keep API calls in a service/client layer rather than scattering fetch logic across UI components.
- Represent loading, empty, error, success, pending, rejected, verified, and reversed states clearly.
- Use accessible labels, keyboard navigation, readable contrast, and responsive layouts.
- Show destructive/sensitive action confirmations and capture reasons when required.
- Do not treat disabled/hidden UI controls as authorization.
- Do not calculate authoritative invoice balances only in the browser.

## 9. Performance and reliability

Initial targets must be validated against hosting capacity and expected usage. Provisional targets:
- Common interactive requests should generally respond within 2 seconds under ordinary initial-release load, excluding large exports and network delays.
- List views should use pagination and database indexes.
- Reports must filter and aggregate in the database where appropriate.
- Avoid N+1 database query patterns.
- Provide health/readiness checks suitable for deployment.
- Log errors with correlation/request identifiers while avoiding sensitive data.
- Define backup frequency, retention, recovery-point objective (RPO), and recovery-time objective (RTO) before launch.

## 10. Testing requirements

Minimum automated coverage:
- Unit tests for invoice calculations, allocation limits, verification rules, reversal behavior, and permission checks.
- Integration tests against PostgreSQL for migrations, constraints, and transactions.
- API tests for authentication, company isolation, validation, and error cases.
- Frontend tests for critical forms and state transitions.
- End-to-end smoke tests for key user journeys.
- Regression tests for every fixed financial or authorization defect.

Do not rely solely on mocked database tests for critical financial constraints.

## 11. Environments and configuration

Define separate local/development, test, and production configuration. Use environment variables or a secure secret store for:
- database URL, username, and password;
- signing/session secrets;
- allowed frontend origins;
- file-storage configuration;
- email or other external service settings, if later approved.

Provide a safe `.env.example` containing placeholders only. Ensure actual `.env` files and secrets are ignored by Git.

## 12. Deployment requirements

Before production:
- deploy only a tested build;
- run migrations through a controlled process;
- configure HTTPS and secure headers;
- configure least-privilege database access;
- verify backups and a restore drill;
- configure application and database monitoring/logging;
- create a secure initial administrator bootstrap procedure;
- document rollback/recovery steps;
- run a production-readiness checklist.

## 13. Repository and coding-agent rules

OpenCode must:
1. Inspect the current repository and Git status before changes.
2. Preserve existing work and avoid destructive commands.
3. Read all approved documents before implementation.
4. Implement one phase at a time.
5. Explain planned file changes before broad architectural changes.
6. Run relevant tests and report actual results; never claim unrun tests passed.
7. Ask before changing an approved business rule, stack, schema contract, or dependency strategy.
8. Keep documentation updated when an approved decision changes.
9. Never place credentials or real customer/payment data in source control.
10. Stop and report when requirements conflict or a critical decision is unresolved.

## 14. Open decisions

- Exact Java, Spring Boot, Node.js, and PostgreSQL versions.
- Whether to use Docker for local development/deployment.
- Authentication/token strategy.
- File evidence storage method.
- Deployment host and domain.
- Backup/restore targets.
- Whether PostgreSQL row-level security will supplement backend authorization.
