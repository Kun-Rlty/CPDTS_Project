package com.cpdts.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cpdts.service.InvoiceManagementService;

/**
 * Company-scoped invoice endpoints (PRD FR-04). No DELETE route exists:
 * draft invoices are edited, issued invoices are voided with a reason, and
 * voided invoices are terminal (the V2 database trigger enforces the
 * no-delete rule as well).
 *
 * <ul>
 *   <li>GET   /api/v1/companies/{companyId}/invoices - bounded paginated
 *       list/search with derived balance state (RECORD_VIEW)</li>
 *   <li>POST  /api/v1/companies/{companyId}/invoices - create a DRAFT with
 *       server-generated number and server-calculated total
 *       (INVOICE_MANAGE)</li>
 *   <li>GET   /api/v1/companies/{companyId}/invoices/{invoiceId} - detail
 *       with items (RECORD_VIEW)</li>
 *   <li>PATCH /api/v1/companies/{companyId}/invoices/{invoiceId} - edit a
 *       DRAFT (INVOICE_MANAGE)</li>
 *   <li>POST  /api/v1/companies/{companyId}/invoices/{invoiceId}/issue -
 *       DRAFT -&gt; ISSUED (INVOICE_MANAGE)</li>
 *   <li>POST  /api/v1/companies/{companyId}/invoices/{invoiceId}/void -
 *       DRAFT/ISSUED -&gt; VOIDED with a mandatory reason (INVOICE_MANAGE)</li>
 * </ul>
 *
 * <p>All routes are authenticated and CSRF-protected by the existing filter
 * chain. Authorization, company scoping, lifecycle rules, numbering, totals
 * and audit live in {@link InvoiceManagementService}. Request DTOs contain
 * NO id/number/total/currency/status/company fields: server-computed and
 * tenant identifiers are never read from the client (unknown JSON fields are
 * ignored by Jackson 3).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/invoices")
public class InvoiceManagementController {

    private final InvoiceManagementService service;

    public InvoiceManagementController(InvoiceManagementService service) {
        this.service = service;
    }

    @GetMapping
    public InvoicePage list(@PathVariable long companyId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId) {
        return service.listInvoices(companyId, page, size, q, status, customerId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetailDto create(@PathVariable long companyId,
            @RequestBody CreateInvoiceRequest body) {
        return service.createInvoice(companyId, body);
    }

    @GetMapping("/{invoiceId}")
    public InvoiceDetailDto detail(@PathVariable long companyId, @PathVariable long invoiceId) {
        return service.getInvoice(companyId, invoiceId);
    }

    @PatchMapping("/{invoiceId}")
    public InvoiceDetailDto updateDraft(@PathVariable long companyId,
            @PathVariable long invoiceId, @RequestBody UpdateDraftInvoiceRequest body) {
        return service.updateDraft(companyId, invoiceId, body);
    }

    @PostMapping("/{invoiceId}/issue")
    public InvoiceDetailDto issue(@PathVariable long companyId,
            @PathVariable long invoiceId) {
        return service.issueInvoice(companyId, invoiceId);
    }

    @PostMapping("/{invoiceId}/void")
    public InvoiceDetailDto voidInvoice(@PathVariable long companyId,
            @PathVariable long invoiceId, @RequestBody VoidInvoiceRequest body) {
        return service.voidInvoice(companyId, invoiceId, body);
    }

    // ------------------------------------------------------------------
    // Request/response DTOs
    // ------------------------------------------------------------------

    /**
     * invoiceNumber / totalAmount / currency / status are intentionally
     * absent: the number comes from the company's sequence, the total is
     * calculated from the items, the currency is TZS, and new invoices are
     * always DRAFT. customerId is validated against the PATH company - a
     * foreign customer is 404, never accepted.
     */
    public record CreateInvoiceRequest(Long customerId, String issueDate, String dueDate,
            String description, List<InvoiceItemRequest> items) {
    }

    /**
     * DRAFT-only edit. Every field optional but at least one required; a
     * provided {@code items} list REPLACES the whole item set and the total
     * is recomputed. Dates are ISO yyyy-MM-dd strings. The customer and
     * currency are deliberately immutable after creation.
     */
    public record UpdateDraftInvoiceRequest(String issueDate, String dueDate,
            String description, List<InvoiceItemRequest> items) {
    }

    /** Void reason is mandatory (V2 constraint ck_invoices_voided). */
    public record VoidInvoiceRequest(String reason) {
    }

    /**
     * quantity is NUMERIC(12,3) (positive, max 3 decimals); unitPrice is
     * NUMERIC(14,2) (non-negative, max 2 decimals). lineTotal is never
     * accepted from the client - it is a database-generated column and the
     * service recomputes it with the same rounding rule.
     */
    public record InvoiceItemRequest(String description, BigDecimal quantity,
            BigDecimal unitPrice) {
    }

    /** One line item as stored (lineTotal from the generated column). */
    public record InvoiceItemDto(Long id, String description, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal lineTotal, Integer sortOrder) {
    }

    /**
     * List-row view: no items (detail carries them), but includes the
     * DERIVED balance state. derivedStatus is DRAFT/ISSUED/VOIDED for
     * non-issued invoices and UNPAID/PARTIALLY_PAID/PAID/OVERDUE for issued
     * ones (never stored - recomputed from ACTIVE allocations of VERIFIED
     * payments on every read).
     */
    public record InvoiceSummaryDto(Long id, String invoiceNumber, Long customerId,
            String customerCode, String customerName, LocalDate issueDate, LocalDate dueDate,
            String currencyCode, String status, BigDecimal totalAmount,
            BigDecimal outstandingAmount, String derivedStatus, String description,
            Instant issuedAt, Instant voidedAt, Instant createdAt, Instant updatedAt) {
    }

    /** Full invoice including its items in creation order. */
    public record InvoiceDetailDto(Long id, String invoiceNumber, Long customerId,
            String customerCode, String customerName, LocalDate issueDate, LocalDate dueDate,
            String currencyCode, String status, BigDecimal totalAmount,
            BigDecimal outstandingAmount, String derivedStatus, String description,
            Instant issuedAt, Instant voidedAt, Instant createdAt, Instant updatedAt,
            List<InvoiceItemDto> items) {
    }

    /** One bounded page; totals computed server-side, never trusted. */
    public record InvoicePage(List<InvoiceSummaryDto> items, int page, int size,
            long totalElements, int totalPages) {
    }
}
