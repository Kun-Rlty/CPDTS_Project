# Product Requirements Document (PRD)
## Customer Payment & Debt Tracking System (CPDTS)

- **Document status:** Draft for review before development
- **Version:** 1.0
- **Last updated:** 27 October 2026
- **Product owner:** To be confirmed
- **Primary currency:** Tanzanian Shilling (TZS)

## 1. Product overview

CPDTS is a web-based system for recording customers, invoices/debts, incoming payments, payment verification, allocations, balances, overdue accounts, and financial reports for multiple companies from one controlled platform.

The initial deployment is intended to support:
1. **JEIMAX INVESTMENTS COMPANY** — company code `JMX`
2. **KAMWERUKA SECURITY & SAFETY ADMINISTRATOR CO. LTD.** — company code `KAM`

The platform must keep each company's operational and financial records isolated while allowing an authorized platform administrator to oversee both companies.

## 2. Problem statement

Manual or fragmented debt and payment records can make it difficult to establish:
- how much a customer owes;
- which invoices a payment was intended to settle;
- whether a payment has actually been checked and approved;
- which invoices are partly or fully paid;
- which accounts are overdue;
- who created, changed, verified, rejected, or reversed a financial record.

CPDTS aims to provide a consistent, auditable record of these activities.

## 3. Product goals

1. Maintain accurate customer, invoice, and payment records.
2. Support full and partial payments.
3. Require a separate authorized person to verify a payment.
4. Reduce invoice balances only by allocations from verified payments.
5. Make overdue balances and payment history easy to inspect.
6. Separate company data and permissions.
7. Preserve an audit trail for important financial actions.
8. Provide useful reports and export-ready views.
9. Deliver a secure, maintainable first release that can be extended later.

## 4. Non-goals for the first release

- Direct integration with banks, mobile-money providers, or payment gateways.
- Automatic bank-statement reconciliation.
- A native Android/iOS application.
- Multi-currency accounting; TZS is the initial currency.
- Full general-ledger accounting, payroll, tax filing, or inventory management.
- Automatic SMS/WhatsApp/email delivery unless separately approved.
- Treating uploaded evidence as proof of payment without human verification.

## 5. Users and access

### 5.1 User categories

- **Platform Administrator:** Can manage the platform and access both configured companies, subject to audit logging.
- **Company Administrator:** Manages users and company settings only within assigned company scope.
- **Finance/Cashier User:** Creates invoices and/or records incoming payments according to assigned permissions.
- **Payment Verifier:** Reviews payment details and evidence, then verifies or rejects a payment. Must not verify a payment they recorded.
- **Read-only/Auditor:** Views permitted records and reports without modifying financial records.

The exact role catalogue and permission matrix must be approved before implementation. Roles should be permission-based rather than relying only on hard-coded role names.

### 5.2 Company access rules

- Every business record belongs to one company.
- A normal user may access only companies for which they have an active membership.
- The platform administrator may access both `JMX` and `KAM`.
- Company scope must be checked on the server for every request; hiding a company in the UI is not security.
- Cross-company references must be rejected by the database and/or backend validation.
- Access changes and privileged cross-company actions must be audited.

## 6. Core functional requirements

### FR-01: Authentication and user management
- Users can sign in and sign out securely.
- Passwords must be stored using a modern adaptive password hash, never plaintext.
- Unauthorized users cannot access protected endpoints.
- Administrators can activate/deactivate users and manage company memberships.
- Account recovery and session/token lifecycle must be defined in the technical design.

### FR-02: Company management
- Store each company's legal/display name, unique code, status, and timestamps.
- Initial companies are `JMX` and `KAM`.
- Company codes must be unique and should not be casually changed after use.

### FR-03: Customer management
- Create, view, update, search, and deactivate customers within a company.
- Store relevant customer identity/contact information and internal notes as approved.
- Prevent accidental duplicates using carefully chosen company-scoped identifiers where available.
- Do not require a national identity number unless the business confirms a lawful need.

### FR-04: Invoice/debt management
- Create invoices for a customer within the same company.
- Support invoice line items, issue date, due date, description, and status.
- Invoice number must be unique within a company; recommended format: `JMX-INV-00001` and `KAM-INV-00001`.
- Invoice totals must be calculated by trusted backend logic from line items, not trusted from client-submitted totals.
- Issued financial records must not be silently deleted. Corrections should use a controlled void/correction process with a reason and audit trail.
- Draft, issued, voided, and other statuses must be finalized in the schema/design review.

### FR-05: Payment recording
- Record cash, bank transfer, or mobile-money payments.
- Capture amount, received/recorded date, method, reference where available, evidence metadata, recorder, company, and relevant customer.
- A payment may be pending verification after recording.
- Duplicate reference warnings/checks should be considered, but a reference may be absent for cash payments.
- Do not claim an external transfer has been confirmed merely because evidence was uploaded.

### FR-06: Payment verification
- An authorized verifier reviews the payment and supporting evidence manually.
- The person who recorded the payment cannot verify or reject that same payment.
- Verification/rejection must capture verifier, timestamp, decision, and reason/notes.
- Preserve verification history; do not overwrite history without trace.
- A rejected payment must not reduce any invoice balance.
- Only verified payments may be allocated to invoices and reduce balances.
- Reversals must be a separately authorized, audited action, not silent deletion.

### FR-07: Payment allocation and partial payments
- A payment can be allocated across one or more invoices.
- An invoice can receive allocations from multiple payments.
- Support partial settlement.
- Prevent allocating more than the verified available payment amount.
- Prevent allocations from exceeding an invoice's remaining collectible balance unless an explicitly approved overpayment/credit policy is introduced.
- Payment and invoice must belong to the same company.
- Verification and allocation/balance effects must be processed transactionally to avoid race conditions.

### FR-08: Balances and overdue accounts
- Outstanding balance is based on the invoice total minus valid allocations from verified, non-reversed payments, according to the approved reversal model.
- A positive balance with a due date before the current business date is overdue.
- The UI must distinguish pending, rejected, verified, and reversed payments.
- Derived balances must be calculated consistently by backend queries/services; users must not edit a balance field directly.

### FR-09: Audit trail
Record at least:
- user and timestamp;
- company scope;
- action/entity and record identifier;
- action type;
- meaningful before/after information or a safe structured change summary;
- reason for sensitive actions where required;
- request/correlation identifier where practical.

Audit at minimum customer changes, invoice issuance/correction, payment recording, verification/rejection, allocation, reversal, membership/permission changes, and privileged cross-company access. Audit records should be protected from ordinary modification/deletion.

### FR-10: Search, dashboard, and reports
- Search customers, invoices, and payments within authorized company scope.
- Show totals such as outstanding debt, overdue debt, pending verification, and verified collections.
- Provide customer statement/payment history.
- Filter reports by company, date range, customer, invoice status, and payment status where applicable.
- Clearly label whether a figure is a payment recorded, verified, allocated, or outstanding amount.
- Export format(s), such as CSV/PDF/XLSX, must be selected before implementation.

## 7. Business rules

1. Currency is TZS; store money using PostgreSQL `NUMERIC`, never floating-point types.
2. Every business record has a company scope.
3. Cross-company customer/invoice/payment relationships are invalid.
4. A payment recorder cannot verify their own payment.
5. Pending and rejected payments do not reduce invoice balances.
6. Only valid allocations from verified payments affect balances.
7. Partial payments are supported.
8. Invoice totals are calculated by backend logic.
9. Important financial records are corrected/reversed with a reason and audit trail, not silently deleted.
10. Multi-row financial operations must be transaction-safe.
11. The UI is not the security boundary; authorization is enforced on the backend.
12. No external payment provider integration is included in the initial release.

## 8. Acceptance criteria for the first release

The release is acceptable only when all of the following are demonstrated in tests:
- A user assigned only to `JMX` cannot read or change `KAM` data by changing a URL, ID, request body, or API parameter.
- An authorized platform administrator can switch between both companies and the access is auditable.
- A user cannot verify a payment they recorded, even by calling the API directly.
- A pending or rejected payment does not change invoice balance.
- A verified payment allocation reduces the appropriate invoice balance correctly.
- Partial payments and payments allocated to multiple invoices work correctly.
- The system prevents invalid over-allocation and cross-company allocation.
- Concurrent verification/allocation attempts cannot produce inconsistent balances.
- Reversals/corrections retain a traceable history.
- Reports reconcile with the underlying records and distinguish payment states.
- Database migrations can create a fresh database successfully and are tested against the selected PostgreSQL version.
- Automated tests cover authorization, core financial rules, and important failure cases.

## 9. Risks and decisions still requiring approval

- Exact role/permission matrix.
- Invoice lifecycle and whether issued invoices may be edited, voided, or credited.
- Whether overpayments are rejected, held as unapplied credit, or handled through a separate credit balance.
- Required evidence types and file storage limits.
- Which fields are mandatory for each payment method.
- Report/export formats.
- Whether customers can exist in more than one company or must be separate company-scoped records.
- Data retention, backups, recovery objectives, and deployment host.
- Whether PostgreSQL row-level security is required in addition to backend tenant checks.

## 10. Definition of ready for development

Development may begin when stakeholders review and approve this PRD, technical requirements, app flow, design brief, backend schema, and implementation plan together. Unresolved decisions must be marked explicitly rather than guessed by the coding agent.
