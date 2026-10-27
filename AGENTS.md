# CPDTS — Project Instructions for OpenCode

## Mission
Build a production-ready, maintainable multi-company Client Payment & Debt Tracking System in controlled, reviewable phases. Start by auditing the repository and reading all documents in `docs/`.

## Working rules
1. Before coding: inspect existing files, Git status, tools, runtime, and actual database state. Never assume they exist.
2. Follow the six `docs/*.md` documents. Surface contradictions and unresolved business decisions; do not silently invent policies.
3. Work on **one approved phase at a time**. Explain plan and files to change before making changes. Do not expand scope.
4. Preserve working architecture; avoid unnecessary rewrites, frameworks, and dependencies.
5. Proposed stack (subject to confirmation): React + TypeScript; Spring Boot REST API; PostgreSQL; Flyway migrations; modular monolith.
6. Tenant isolation: main platform admin may access both JMX and KAM; other users access only companies assigned via active memberships. Enforce in backend and database relationships, not just UI.
7. Money: use exact decimal types (e.g. PostgreSQL NUMERIC(14,2)); use TZS. Compute invoice totals on server.
8. Payment creator must never verify their own payment. Pending/rejected payments must never reduce invoice balances. Only eligible verified allocations reduce balances.
9. Preserve audit history. No silent deletion of financial records. Require approved reversal/void behavior.
10. Use database transactions and concurrency controls for payment verification/allocation and invoice balances. Reject cross-company references.
11. Do not add live payment gateway integrations in MVP. Payment evidence is reviewed manually.
12. Never store plaintext credentials or secrets in source control. Never seed known admin passwords.
13. After each approved implementation step: run relevant checks/tests, report actual results and remaining issues. Never claim tests passed without running them.
14. Prompts and code comments should be in English unless explicitly requested otherwise.

## First action
Run the audit prompt in `prompts/01_AUDIT_FIRST.md`. Do not modify the project until the user approves the proposed next step.
