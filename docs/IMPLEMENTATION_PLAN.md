# Implementation Plan
## Customer Payment & Debt Tracking System (CPDTS)

- **Status:** Proposed plan; begin only after documentation review
- **Version:** 1.0
- **Last updated:** 27 October 2026
- **Working mode:** OpenCode coding agent with human review at each checkpoint

## 1. Guiding principles

- Audit the existing repository before making changes.
- Build from approved requirements, not guesses.
- Implement in small, testable phases.
- Preserve existing work and Git history.
- Treat company isolation and financial correctness as core requirements, not final polish.
- Never claim a test, migration, or deployment succeeded unless it was actually run and its result checked.
- Do not begin the next phase until its exit criteria pass.

## 2. Phase 0 — Requirements and repository audit

### Tasks
1. Inspect repository root, nested projects, README, package/build files, configuration, existing database migrations, and Git status.
2. Determine whether a frontend/backend already exists and record its actual stack/version.
3. Check PostgreSQL installation/connectivity separately from whether the CPDTS database exists.
4. Review the six documentation files together.
5. Resolve open decisions in PRD and backend schema.
6. Create an architecture decision record for important stack/schema decisions if useful.

### Deliverables
- Repository audit report.
- Approved documentation.
- List of open risks/decisions.

### Exit criteria
- No critical business rule remains guessed.
- Actual project state is known.
- Existing work is protected by Git and/or backup.

## 3. Phase 1 — Development environment and project skeleton

### Tasks
1. Install/configure only missing approved tools.
2. Confirm supported Java, Node.js/package manager, and PostgreSQL versions.
3. Configure environment variables and a safe `.env.example`.
4. Set up or verify frontend/backend structure according to repository findings.
5. Configure formatting, linting, test commands, and basic CI where practical.
6. Establish local run instructions.

### Tests/checks
- Frontend starts.
- Backend starts.
- Backend can connect to the intended local PostgreSQL instance.
- Secrets are not tracked by Git.
- Existing tests still pass.

### Exit criteria
A developer can reproduce the setup from the documented instructions without guessing.

## 4. Phase 2 — Finalize ERD and PostgreSQL migrations

### Tasks
1. Confirm entity names, IDs, relationships, constraints, and company-scoped keys.
2. Review ERD with the business rules in the PRD.
3. Write Flyway migrations in dependency order.
4. Add only safe reference seed data (`JMX`, `KAM`, approved roles/permissions).
5. Run migrations against a new empty test database.
6. Run migrations against the local development database.
7. Test constraints and document reset/backup procedures for development.

### Tests/checks
- Clean database migration succeeds.
- Foreign keys and unique/check constraints behave as expected.
- Cross-company relationships are rejected.
- Money values use fixed precision.
- No plaintext/default administrator credential is seeded.

### Exit criteria
ERD and migrations match; all schema tests pass; changes are committed.

## 5. Phase 3 — Authentication, permissions, and company isolation

### Tasks
1. Implement sign-in and secure password hashing.
2. Implement users, roles/permissions, and active company memberships.
3. Implement trusted company context on the server.
4. Add authorization checks to every protected endpoint.
5. Add platform-admin access rules for both companies.
6. Add audit events for membership/permission changes.
7. Add secure bootstrap for the initial administrator.

### Tests/checks
- Unauthenticated requests are rejected.
- User without membership cannot access a company.
- User assigned to `JMX` cannot access `KAM` data by changing IDs/parameters.
- Platform administrator can access both only under approved rules.
- Permission changes take effect as designed.

### Exit criteria
Authorization tests pass before financial modules are opened to users.

## 6. Phase 4 — Customer management

### Tasks
1. Create customer backend APIs and validation.
2. Implement list/search/detail/create/update/deactivate operations.
3. Add company scope to all queries and writes.
4. Implement audit events.
5. Build frontend screens and error/empty/loading states.

### Tests/checks
- Required fields and duplicate rules work.
- Cross-company customer access is denied.
- Audit trail captures important changes.

### Exit criteria
Customer workflow works end-to-end for both companies without data leakage.

## 7. Phase 5 — Invoice and debt management

### Tasks
1. Implement invoice and invoice-item schema/API behavior.
2. Implement backend total calculation and invoice-number generation.
3. Implement draft/issue/void/correction workflow as approved.
4. Implement invoice details, balances, search, and filters.
5. Add audit history for issuance and corrections.

### Tests/checks
- Totals are calculated server-side.
- Invoice number is unique within company.
- Customer and invoice company scopes match.
- Voided/corrected invoices follow approved rules.
- Balances cannot be edited directly.

### Exit criteria
Invoice lifecycle and totals are correct and tested.

## 8. Phase 6 — Payment recording and verification

### Tasks
1. Implement payment recording for cash, bank transfer, and mobile money.
2. Validate amounts, method-specific fields, company, and references.
3. Implement pending verification queue.
4. Implement manual verification/rejection with history and reasons.
5. Enforce that recorder and verifier differ.
6. Add evidence handling only after its storage/retention policy is approved.
7. Add audit events for every sensitive transition.

### Tests/checks
- Pending/rejected payment does not reduce balances.
- Self-verification is blocked at the backend.
- Verification decisions are traceable.
- Invalid amounts and unauthorized requests fail safely.

### Exit criteria
Payment lifecycle is correct before allocations are enabled.

## 9. Phase 7 — Allocations, balances, and reversals

### Tasks
1. Implement allocation of verified payments to one or more eligible invoices.
2. Support partial payments.
3. Add transactional locking/rechecks for concurrent operations.
4. Calculate invoice outstanding and payment unallocated amounts.
5. Implement approved reversal/correction workflow.
6. Ensure report queries apply the same eligibility rules as balance queries.

### Tests/checks
- Payment cannot be over-allocated.
- Invoice cannot be over-allocated unless the approved credit model permits it.
- Cross-company allocations fail.
- Concurrent allocation attempts preserve consistent balances.
- Reversal restores/recalculates financial effects correctly without deleting history.

### Exit criteria
Core financial invariants pass integration tests against PostgreSQL.

## 10. Phase 8 — Dashboard, reports, and exports

### Tasks
1. Implement dashboard summaries.
2. Implement customer statements and invoice/payment history.
3. Implement overdue and pending-verification reports.
4. Add filters, pagination, and approved exports.
5. Audit sensitive exports where required.

### Tests/checks
- Figures reconcile with source records.
- Report scopes never cross company boundaries.
- Payment recorded, verified, allocated, reversed, and outstanding amounts are not confused.
- Date boundaries and timezone rules are tested.

### Exit criteria
Reports reconcile with known test scenarios and are access-controlled.

## 11. Phase 9 — Hardening, UAT, and production readiness

### Tasks
1. Run full automated test suite and critical manual test journeys.
2. Review security configuration, dependency risks, file uploads, logs, and secrets.
3. Test backups and database restoration.
4. Configure HTTPS, production environment, monitoring, and least-privilege credentials.
5. Create initial admin bootstrap procedure.
6. Prepare user guide and operational runbook.
7. Conduct user acceptance testing (UAT) with representative users.
8. Fix release-blocking issues and document known limitations.

### Exit criteria
- Critical authorization and financial tests pass.
- Backup restoration is demonstrated.
- Production configuration is reviewed.
- Users approve core workflows.
- Release/rollback plan is documented.

## 12. Phase 10 — Controlled launch and maintenance

### Tasks
1. Deploy the approved build.
2. Run migrations through the controlled release process.
3. Verify health checks and critical workflows.
4. Monitor errors and financial reconciliation.
5. Train users and provide support instructions.
6. Schedule backup verification, dependency updates, and periodic access reviews.

### Exit criteria
The system is in operational use with an owner for support, backups, and incident response.

## 13. Suggested Git workflow

- Keep the main branch in a deployable state where practical.
- Use short feature branches with clear names.
- Make focused commits after tests pass.
- Review diffs before merging.
- Do not commit secrets, local database dumps, real payment evidence, or personal customer data.
- Back up uncommitted work before risky refactors.
- Never run destructive database resets against a database containing real data.

## 14. Definition of done for each task

A task is complete only when:
- requirements are met;
- code is readable and follows the existing architecture;
- relevant tests have actually run and passed;
- company authorization and financial side effects have been checked;
- errors and edge cases are handled;
- documentation is updated if behavior/schema changed;
- the diff has been reviewed;
- no unrelated files or existing work were accidentally changed.

## 15. OpenCode execution prompt

Use this as the baseline instruction for every implementation task:

> Read `docs/PRD.md`, `docs/TECHNICAL_REQUIREMENTS.md`, `docs/APP_FLOW.md`, `docs/DESIGN_BRIEF.md`, `docs/BACKEND_SCHEMA.md`, and `docs/IMPLEMENTATION_PLAN.md` before changing code.
>
> First inspect the current repository, Git status, and relevant files. Do not assume a framework, database, configuration, or feature exists until verified. Do not overwrite or delete existing work. Implement only the current approved phase/task. Before editing, summarize the intended changes and identify any conflict with the documents. Preserve company isolation, payment-verification separation, transaction safety, and audit requirements. Run the relevant tests and report the exact commands and actual outcomes. Do not claim tests passed if they were not run. If an unresolved business decision or document conflict blocks safe implementation, stop and ask for clarification. Update documentation when an approved decision changes.
