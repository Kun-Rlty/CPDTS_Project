# Design Brief
## Customer Payment & Debt Tracking System (CPDTS)

- **Status:** Proposed design direction
- **Version:** 1.0
- **Last updated:** 27 October 2026

## 1. Design objective

Create a professional, calm, reliable financial operations interface for staff who need to record debts and payments accurately. The interface should prioritize clarity, traceability, and efficient daily work over decorative effects.

## 2. Target users and context

Primary users are company administrators, finance/cashier staff, payment verifiers, and read-only auditors. Users may have different levels of computer confidence, so forms should be explicit, errors understandable, and high-impact actions difficult to perform accidentally.

The application is intended for desktop and laptop work first, while remaining usable on tablets and mobile screens.

## 3. Visual direction

- Professional, modern, restrained business software.
- Light interface by default, with clear contrast and generous spacing.
- Use a consistent type scale and spacing system.
- Avoid excessive gradients, glass effects, animated decoration, and unnecessary charts.
- Use color to communicate state, not as the only way to convey meaning.
- Use one coherent icon set and consistent button styles.
- Avoid making the interface look like a consumer shopping app.

A final brand palette and logo treatment should be approved before detailed UI implementation. Do not invent or modify company logos without source assets/approval.

## 4. Information architecture

Primary navigation:
1. Dashboard
2. Customers
3. Invoices / Debts
4. Payments
5. Reports
6. Administration (permission-gated)

Payments sub-navigation:
- Record Payment
- Pending Verification
- Payment History
- Reversals, if authorized

Administration sub-navigation:
- Users and Memberships
- Company Settings
- Audit Log

The active company must be visible in the application header. Users with multi-company access may switch companies through a clear selector. Only permitted companies may appear.

## 5. Dashboard requirements

The dashboard should answer:
- What is the total outstanding debt?
- What amount is overdue?
- How many payments are waiting for verification?
- What amount has been verified within the selected period?
- Which customers/invoices need attention?

Use summary cards sparingly. Every financial figure must be labeled with its meaning, company scope, and period when relevant. Do not mix recorded payments with verified collections. Provide clear links from summaries to the filtered records behind them.

## 6. Data table design

Customer, invoice, payment, and audit tables should support:
- clear column headings;
- server-side pagination;
- search and useful filters;
- sorting where meaningful;
- empty states that explain how to begin;
- loading and error states;
- readable dates and TZS amounts;
- responsive overflow behavior;
- row actions controlled by permissions;
- confirmation for high-impact actions.

Do not rely on color alone to convey payment status; use a text label plus optional status color.

## 7. Status language

Use consistent status labels throughout the system:
- `Pending verification`
- `Verified`
- `Rejected`
- `Reversed`

Invoice statuses must be finalized with the backend schema. Suggested visual states include Draft, Issued, Partially paid, Paid, Overdue, and Voided, but derived statuses such as Overdue/Partially paid/Paid should not be confused with stored lifecycle statuses.

## 8. Forms

### General rules
- Group related fields.
- Mark required fields clearly.
- Show field-level validation messages next to the relevant field.
- Preserve safe form input after recoverable validation errors.
- Disable repeated submission only as a usability aid; the backend must still prevent duplicate financial operations.
- Explain what happens after submission.
- Display company context on forms.

### Payment form
Fields to consider:
- Customer
- Amount (TZS)
- Received date/time
- Method: Cash / Bank transfer / Mobile money
- Reference number, where available
- Evidence upload or evidence metadata, if approved
- Notes

The form must say that the payment will be pending manual verification and will not reduce invoice balances until verified and properly allocated.

### Verification screen
Show the recorded payment details, recorder, date/time, method, reference, and accessible evidence. Make the separation between recorder and verifier explicit. Verification/rejection actions require a clear confirmation; rejection requires a reason.

### Allocation screen
Show the verified payment amount, previously allocated amount, remaining amount, selected invoices, invoice balances, and proposed allocation totals. Explain validation errors in plain language.

## 9. Money and date presentation

- Display currency consistently as TZS, e.g. `TZS 125,000.00` (final decimal-display convention may be approved).
- Do not use floating-point arithmetic in frontend calculations for authoritative financial outcomes.
- Use a consistent date/time display and clarify timezone where relevant.
- Make due dates and overdue status easy to identify.
- Keep raw technical timestamps out of ordinary user-facing screens.

## 10. Accessibility and usability

- Maintain readable contrast.
- Support keyboard navigation and visible focus.
- Use labels for form controls and meaningful button text.
- Provide text alternatives for icon-only actions.
- Do not communicate errors or status using color alone.
- Use confirmation and reason prompts for sensitive financial actions.
- Avoid tiny text and dense forms where practical.

## 11. Responsive behavior

- Desktop: persistent navigation and full data tables.
- Tablet: collapsible navigation and adapted table layouts.
- Mobile: stacked cards/forms and horizontally scrollable or simplified tables; prioritize essential columns and actions.
- No core workflow should require hover-only interaction.

## 12. Content tone

Use concise, neutral, professional language. Prefer:
- “Payment recorded. Awaiting verification.”
- “This payment cannot be verified by the person who recorded it.”
- “Allocation exceeds the invoice's remaining balance.”
- “You do not have access to this company's records.”

Avoid vague errors such as “Something went wrong” when a safe, actionable explanation can be given. Never reveal whether a private record exists in another company.

## 13. Required interface states

For each key page, design:
- initial/empty state;
- loading state;
- success state;
- validation-error state;
- permission-denied state;
- server/network error state;
- no-results state;
- pending/review state where relevant.

## 14. Suggested pages for first release

1. Sign in
2. Company selection (when needed)
3. Dashboard
4. Customer list and customer profile
5. Create/edit customer
6. Invoice list and invoice details
7. Create invoice
8. Payment list/history
9. Record payment
10. Pending verification queue
11. Payment verification detail
12. Payment allocation
13. Reports
14. User/membership administration
15. Audit log
16. Access denied / not found / error pages

## 15. Design acceptance criteria

- Users can identify the active company on every business-data page.
- Pending, verified, rejected, and reversed payment states are distinguishable without color alone.
- Payment recording clearly communicates that verification is still required.
- The verification page identifies the recorder and prevents self-verification.
- Invoice and payment screens show totals, allocations, and remaining balances with clear labels.
- Unauthorized actions are hidden or disabled for usability, and still denied by the backend.
- Key workflows are usable with keyboard navigation and on a small screen.
- Loading, empty, error, and success states are designed before a page is considered complete.
