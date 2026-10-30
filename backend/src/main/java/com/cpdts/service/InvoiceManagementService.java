package com.cpdts.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.security.Permissions;
import com.cpdts.web.InvoiceManagementController.CreateInvoiceRequest;
import com.cpdts.web.InvoiceManagementController.InvoiceDetailDto;
import com.cpdts.web.InvoiceManagementController.InvoiceItemDto;
import com.cpdts.web.InvoiceManagementController.InvoiceItemRequest;
import com.cpdts.web.InvoiceManagementController.InvoicePage;
import com.cpdts.web.InvoiceManagementController.InvoiceSummaryDto;
import com.cpdts.web.InvoiceManagementController.UpdateDraftInvoiceRequest;
import com.cpdts.web.InvoiceManagementController.VoidInvoiceRequest;
import com.cpdts.web.ConflictException;
import com.cpdts.web.NotFoundException;

import tools.jackson.databind.ObjectMapper;

/**
 * Company-scoped invoice management (PRD FR-04; implementation-plan Phase 5).
 *
 * <p>Lifecycle (approved in the phase instruction; matches the V2 schema):
 * <ul>
 *   <li>{@code DRAFT} - editable (dates, description, items); may be issued
 *       or voided.</li>
 *   <li>{@code ISSUED} - financial fields and items are immutable; only a
 *       void is allowed.</li>
 *   <li>{@code VOIDED} - terminal; requires a reason; excluded from
 *       collectible debt.</li>
 * </ul>
 * PAID / PARTIALLY PAID / OVERDUE / UNPAID are DERIVED on read (never
 * stored): only ISSUED invoices carry outstanding debt, computed as the
 * stored backend-calculated total minus ACTIVE allocations of VERIFIED,
 * non-reversed payments (the allocations tables exist since V3; the payments
 * phase will create rows in them).
 *
 * <p>Tenant rules: reads require {@code RECORD_VIEW}, mutations require
 * {@code INVOICE_MANAGE} in the TARGET company (resolved per request by
 * {@link AccessControlService}); every lookup and write is scoped by
 * {@code company_id}, so another company's invoice is indistinguishable from
 * a nonexistent one (404). Invoices are NEVER deleted (no DELETE endpoint;
 * the V2 database trigger blocks it as well) - corrections are voids.
 *
 * <p>Creation requires an ACTIVE customer of the SAME company, and issuance
 * re-checks that requirement; historical invoices of since-deactivated
 * customers remain fully readable and voidable.
 *
 * <p>Invoice numbers are generated SERVER-SIDE from the company's
 * {@code number_sequences} row (kind INVOICE) inside the same transaction as
 * the insert, exactly like customer codes: distinct, contiguous numbers under
 * concurrency. Client-supplied numbers, totals and company ownership are
 * never read.
 *
 * <p>Money: totals are computed with {@link BigDecimal} from the line items,
 * rounding each quantity x unit-price line to 2 decimals HALF_UP and summing
 * - byte-identical to the V2 generated column
 * {@code round(quantity * unit_price, 2)} (PostgreSQL rounds half away from
 * zero; every line here is non-negative because quantity &gt; 0 and
 * unit_price &gt;= 0, so HALF_UP and half-away-from-zero coincide).
 *
 * <p>Race control: edit, issue and void all lock the invoice row
 * {@code FOR UPDATE} and re-check the lifecycle status after acquiring the
 * lock, so edit/issue/void races serialize into one winner. Void additionally
 * rejects invoices carrying ACTIVE payment allocations; the same row lock is
 * the coordination contract for the future allocation phase (which must lock
 * the invoice and re-check {@code status = 'ISSUED'} before inserting), and
 * migration V7 adds a database-level backstop trigger in the V3 style.
 *
 * <p>Audit: INVOICE_CREATED / INVOICE_UPDATED / INVOICE_ISSUED /
 * INVOICE_VOIDED rows are written in the SAME transaction as the change with
 * the acting session user as actor and minimized JSON data.
 */
@Service
public class InvoiceManagementService {

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_ISSUED = "ISSUED";
    static final String STATUS_VOIDED = "VOIDED";
    static final String SEQUENCE_KIND = "INVOICE";
    static final String CURRENCY_TZS = "TZS";

    // Field bounds mirror the V2 schema exactly (plus engineering bounds).
    static final int MAX_DESCRIPTION = 1000;
    static final int MAX_ITEM_DESCRIPTION = 500;
    /** Engineering bound on the unbounded TEXT column - not a business rule. */
    static final int MAX_VOID_REASON = 10_000;
    static final int MAX_ITEMS = 1_000;

    /** quantity NUMERIC(12,3): at most 3 decimal places. */
    static final int QUANTITY_SCALE = 3;
    /** unit_price NUMERIC(14,2): at most 2 decimal places. */
    static final int PRICE_SCALE = 2;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");
    private static final BigDecimal MAX_UNIT_PRICE = new BigDecimal("999999999999.99");
    /** total_amount NUMERIC(14,2) upper bound. */
    private static final BigDecimal MAX_TOTAL = new BigDecimal("999999999999.99");
    /** Engineering date guard (not a business policy): 2000-01-01..2100-12-31. */
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;

    public InvoiceManagementService(JdbcTemplate jdbc,
            AccessControlService accessControlService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Reads (RECORD_VIEW) - include the DERIVED balance state
    // ------------------------------------------------------------------

    /**
     * One page of the company's invoices, optionally filtered by lifecycle
     * status and customer, searched by invoice number / description (ILIKE,
     * user input escaped). Summary rows carry the derived outstanding amount
     * and derived status via ONE correlated subquery (no N+1).
     */
    public InvoicePage listInvoices(long companyId, Integer page, Integer size, String q,
            String status, Long customerId) {
        accessControlService.requirePermission(currentActorId(), companyId,
                Permissions.RECORD_VIEW);

        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!normalizedStatus.isEmpty() && !STATUS_DRAFT.equals(normalizedStatus)
                && !STATUS_ISSUED.equals(normalizedStatus) && !STATUS_VOIDED.equals(normalizedStatus)) {
            throw new IllegalArgumentException("status must be DRAFT, ISSUED or VOIDED");
        }

        String search = q == null ? "" : q.trim();
        String searchPattern = search.isEmpty() ? null : "%" + escapeLike(search) + "%";

        StringBuilder where = new StringBuilder("WHERE i.company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        if (!normalizedStatus.isEmpty()) {
            where.append(" AND i.status = ?");
            args.add(normalizedStatus);
        }
        if (customerId != null) {
            // Scoped by company as well: another company's customer id simply
            // matches nothing (no cross-company disclosure).
            where.append(" AND i.customer_id = ?");
            args.add(customerId);
        }
        if (searchPattern != null) {
            where.append("""
                     AND (i.invoice_number ILIKE ? ESCAPE '\\'
                       OR i.description    ILIKE ? ESCAPE '\\')
                    """);
            args.add(searchPattern);
            args.add(searchPattern);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM invoices i " + where,
                Long.class, args.toArray());
        List<InvoiceSummaryDto> items = jdbc.query(
                """
                SELECT i.id, i.invoice_number, i.customer_id, i.issue_date, i.due_date,
                       i.currency_code, i.status, i.total_amount, i.description,
                       i.issued_at, i.voided_at, i.created_at, i.updated_at,
                       c.customer_code, c.display_name AS customer_name,
                       %s AS allocated_amount
                FROM invoices i
                JOIN customers c ON c.id = i.customer_id AND c.company_id = i.company_id
                %s
                ORDER BY i.issue_date DESC, i.id DESC
                LIMIT ? OFFSET ?
                """.formatted(ALLOCATED_SUM_SQL, where),
                (rs, rowNum) -> mapSummary(rs),
                concat(args, pageSize, (long) pageNumber * pageSize).toArray());

        long totalElements = total == null ? 0 : total;
        int totalPages = (int) ((totalElements + pageSize - 1) / pageSize);
        return new InvoicePage(items, pageNumber, pageSize, totalElements, totalPages);
    }

    /** Full invoice (with items, in client order) inside the company, or 404. */
    public InvoiceDetailDto getInvoice(long companyId, long invoiceId) {
        accessControlService.requirePermission(currentActorId(), companyId,
                Permissions.RECORD_VIEW);
        InvoiceDetailDto invoice = findDetailInCompany(companyId, invoiceId)
                .orElseThrow(() -> new NotFoundException("invoice not found in this company"));
        return withItems(companyId, invoiceId, invoice);
    }

    /** Attaches the invoice's items (ordered) to a summary-shaped detail. */
    private InvoiceDetailDto withItems(long companyId, long invoiceId,
            InvoiceDetailDto invoice) {
        List<InvoiceItemDto> items = jdbc.query(
                """
                SELECT id, description, quantity, unit_price, line_total, sort_order
                FROM invoice_items
                WHERE company_id = ? AND invoice_id = ?
                ORDER BY sort_order, id
                """, (rs, rowNum) -> new InvoiceItemDto(
                        rs.getLong("id"),
                        rs.getString("description"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("line_total"),
                        rs.getInt("sort_order")),
                companyId, invoiceId);
        return new InvoiceDetailDto(invoice.id(), invoice.invoiceNumber(), invoice.customerId(),
                invoice.customerCode(), invoice.customerName(), invoice.issueDate(),
                invoice.dueDate(), invoice.currencyCode(), invoice.status(),
                invoice.totalAmount(), invoice.outstandingAmount(), invoice.derivedStatus(),
                invoice.description(), invoice.issuedAt(), invoice.voidedAt(),
                invoice.createdAt(), invoice.updatedAt(), items);
    }

    // ------------------------------------------------------------------
    // Create (INVOICE_MANAGE) - number + total server-side, always DRAFT
    // ------------------------------------------------------------------

    @Transactional
    public InvoiceDetailDto createInvoice(long companyId, CreateInvoiceRequest request) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.INVOICE_MANAGE);

        long customerId = requireActiveCustomerInCompany(companyId,
                request == null ? null : request.customerId());
        LocalDate issueDate = requireDate(request == null ? null : request.issueDate(),
                "issue date is required");
        LocalDate dueDate = requireDate(request == null ? null : request.dueDate(),
                "due date is required");
        requireDateOrder(issueDate, dueDate);
        String description = optionalText(request == null ? null : request.description(),
                MAX_DESCRIPTION, "description");
        List<ValidatedItem> items = validateItems(request == null ? null : request.items());
        BigDecimal total = totalOf(items);

        // Locks the company's INVOICE counter row until this transaction
        // commits (same mechanism as customer codes).
        Long sequence = jdbc.queryForObject("""
                INSERT INTO number_sequences (company_id, kind, next_value)
                VALUES (?, ?, 2)
                ON CONFLICT (company_id, kind) DO UPDATE
                SET next_value = number_sequences.next_value + 1, updated_at = now()
                RETURNING next_value - 1
                """, Long.class, companyId, SEQUENCE_KIND);
        if (sequence == null) {
            throw new IllegalStateException("invoice sequence returned no value");
        }
        String companyCode = jdbc.queryForObject(
                "SELECT code FROM companies WHERE id = ?", String.class, companyId);
        String invoiceNumber = "%s-INV-%05d".formatted(companyCode, sequence);

        Long invoiceId = jdbc.queryForObject("""
                INSERT INTO invoices
                    (company_id, customer_id, invoice_number, issue_date, due_date,
                     currency_code, status, total_amount, description, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, companyId, customerId, invoiceNumber, issueDate, dueDate,
                CURRENCY_TZS, STATUS_DRAFT, total, description, actorId);
        if (invoiceId == null) {
            throw new IllegalStateException("invoice insert returned no id");
        }
        insertItems(companyId, invoiceId, items);

        audit(actorId, companyId, "INVOICE_CREATED", invoiceId, null, Map.of(
                "invoice_number", invoiceNumber,
                "customer_id", customerId,
                "status", STATUS_DRAFT,
                "total_amount", total.toPlainString(),
                "item_count", items.size()), null);

        return getInCompanyOrThrow(companyId, invoiceId);
    }

    // ------------------------------------------------------------------
    // Edit DRAFT (INVOICE_MANAGE)
    // ------------------------------------------------------------------

    /**
     * Edits a DRAFT invoice's dates, description and/or items (a provided
     * item list REPLACES the whole set - the simplest atomic, auditable
     * model; total is recomputed server-side). The customer is deliberately
     * immutable after creation. ISSUED and VOIDED invoices are immutable
     * financial records: 409.
     */
    @Transactional
    public InvoiceDetailDto updateDraft(long companyId, long invoiceId,
            UpdateDraftInvoiceRequest request) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.INVOICE_MANAGE);

        boolean changeDates = request != null
                && (request.issueDate() != null || request.dueDate() != null);
        boolean changeDescription = request != null && request.description() != null;
        boolean changeItems = request != null && request.items() != null;
        if (!changeDates && !changeDescription && !changeItems) {
            throw new IllegalArgumentException(
                    "at least one of issue date, due date, description or items is required");
        }

        // Lock the invoice FOR UPDATE and re-check the lifecycle status after
        // acquiring the lock: this is what serializes edit/issue/void races.
        InvoiceRow current = lockInvoice(companyId, invoiceId);
        if (!STATUS_DRAFT.equals(current.status())) {
            throw new ConflictException("only DRAFT invoices can be edited");
        }

        LocalDate issueDate = current.issueDate();
        LocalDate dueDate = current.dueDate();
        if (changeDates) {
            if (request.issueDate() != null) {
                issueDate = requireDate(request.issueDate(), "issue date is required");
            }
            if (request.dueDate() != null) {
                dueDate = requireDate(request.dueDate(), "due date is required");
            }
            requireDateOrder(issueDate, dueDate);
        }
        String description = changeDescription
                ? optionalText(request.description(), MAX_DESCRIPTION, "description")
                : current.description();
        List<ValidatedItem> items = changeItems ? validateItems(request.items()) : null;
        BigDecimal total = items != null ? totalOf(items) : null;

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE invoices SET updated_at = now(), "
                + "updated_by = ?");
        args.add(actorId);
        if (changeDates) {
            sql.append(", issue_date = ?, due_date = ?");
            args.add(issueDate);
            args.add(dueDate);
            if (!Objects.equals(issueDate, current.issueDate())) {
                putChange(before, after, "issue_date", current.issueDate(), issueDate);
            }
            if (!Objects.equals(dueDate, current.dueDate())) {
                putChange(before, after, "due_date", current.dueDate(), dueDate);
            }
        }
        if (changeDescription) {
            sql.append(", description = ?");
            args.add(description);
            putChange(before, after, "description", current.description(), description);
        }
        if (changeItems) {
            sql.append(", total_amount = ?");
            args.add(total);
            putChange(before, after, "total_amount", current.totalAmount(), total);
        }
        sql.append(" WHERE company_id = ? AND id = ?");
        args.add(companyId);
        args.add(invoiceId);
        jdbc.update(sql.toString(), args.toArray());

        if (changeItems) {
            // Replace the item set inside the same transaction. The items
            // table has no no-delete trigger (only invoices/customers do),
            // and the invoice is still a DRAFT, so replacement is legal.
            jdbc.update(
                    "DELETE FROM invoice_items WHERE company_id = ? AND invoice_id = ?",
                    companyId, invoiceId);
            insertItems(companyId, invoiceId, items);
            after.put("item_count", items.size());
        }

        if (!before.isEmpty() || !after.isEmpty()) {
            audit(actorId, companyId, "INVOICE_UPDATED", invoiceId, before, after, null);
        }
        return getInCompanyOrThrow(companyId, invoiceId);
    }

    // ------------------------------------------------------------------
    // Issue (INVOICE_MANAGE): DRAFT -> ISSUED
    // ------------------------------------------------------------------

    @Transactional
    public InvoiceDetailDto issueInvoice(long companyId, long invoiceId) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.INVOICE_MANAGE);

        InvoiceRow current = lockInvoice(companyId, invoiceId);
        if (!STATUS_DRAFT.equals(current.status())) {
            throw new ConflictException("only DRAFT invoices can be issued");
        }
        if (current.totalAmount().signum() <= 0) {
            throw new IllegalArgumentException(
                    "invoice total must be positive before it can be issued");
        }
        // Issuance requires an ACTIVE customer (PRD: deactivate stops new
        // business but must not erase history); re-checked here because the
        // customer may have been deactivated since the draft was created.
        requireActiveCustomerInCompany(companyId, current.customerId());

        jdbc.update("""
                UPDATE invoices
                SET status = ?, issued_at = now(), updated_at = now(), updated_by = ?
                WHERE company_id = ? AND id = ?
                """, STATUS_ISSUED, actorId, companyId, invoiceId);

        audit(actorId, companyId, "INVOICE_ISSUED", invoiceId,
                Map.of("status", STATUS_DRAFT),
                Map.of("status", STATUS_ISSUED, "total_amount",
                        current.totalAmount().toPlainString()),
                null);
        return getInCompanyOrThrow(companyId, invoiceId);
    }

    // ------------------------------------------------------------------
    // Void (INVOICE_MANAGE): DRAFT/ISSUED -> VOIDED, reason required
    // ------------------------------------------------------------------

    /**
     * Voids a DRAFT or ISSUED invoice with a mandatory reason. An invoice
     * carrying ACTIVE payment allocations can never be voided: the invoice
     * row lock taken here is the coordination point the allocation phase
     * must share (lock the invoice, re-check {@code status = 'ISSUED'}), and
     * the V7 database trigger is the backstop if that contract is ever
     * bypassed. VOIDED is terminal.
     */
    @Transactional
    public InvoiceDetailDto voidInvoice(long companyId, long invoiceId,
            VoidInvoiceRequest request) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.INVOICE_MANAGE);

        String reason = requireText(request == null ? null : request.reason(),
                "void reason is required", MAX_VOID_REASON, "void reason");

        InvoiceRow current = lockInvoice(companyId, invoiceId);
        if (STATUS_VOIDED.equals(current.status())) {
            throw new ConflictException("invoice is already voided");
        }
        Long activeAllocations = jdbc.queryForObject(
                """
                SELECT count(*) FROM payment_allocations
                WHERE company_id = ? AND invoice_id = ? AND status = 'ACTIVE'
                """, Long.class, companyId, invoiceId);
        if (activeAllocations == null || activeAllocations > 0) {
            throw new ConflictException(
                    "invoice has active payment allocations and cannot be voided");
        }

        jdbc.update("""
                UPDATE invoices
                SET status = ?, voided_at = now(), void_reason = ?, updated_at = now(),
                    updated_by = ?
                WHERE company_id = ? AND id = ?
                """, STATUS_VOIDED, reason, actorId, companyId, invoiceId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", STATUS_VOIDED);
        audit(actorId, companyId, "INVOICE_VOIDED", invoiceId,
                Map.of("status", current.status()), after, reason);
        return getInCompanyOrThrow(companyId, invoiceId);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Sum of ACTIVE allocations of VERIFIED, non-reversed payments against
     * the invoice. A reversed payment has status REVERSED (never VERIFIED),
     * so the status filter alone excludes it - matching the V3 design note
     * that balance derivation stays in the backend.
     */
    private static final String ALLOCATED_SUM_SQL = """
            (SELECT COALESCE(SUM(a.amount), 0)
             FROM payment_allocations a
             JOIN payments p ON p.id = a.payment_id AND p.company_id = a.company_id
             WHERE a.invoice_id = i.id
               AND a.company_id = i.company_id
               AND a.status = 'ACTIVE'
               AND p.status = 'VERIFIED')""";

    /** Locks one invoice row FOR UPDATE within the caller's transaction. */
    private InvoiceRow lockInvoice(long companyId, long invoiceId) {
        return jdbc.query(
                """
                SELECT id, status, issue_date, due_date, total_amount, customer_id, description
                FROM invoices
                WHERE company_id = ? AND id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new InvoiceRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getDate("issue_date").toLocalDate(),
                        rs.getDate("due_date").toLocalDate(),
                        rs.getBigDecimal("total_amount"),
                        rs.getLong("customer_id"),
                        rs.getString("description")),
                companyId, invoiceId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("invoice not found in this company"));
    }

    /**
     * The customer must exist in the SAME company (404 otherwise - no
     * cross-company disclosure) and be ACTIVE for new business (409).
     */
    private long requireActiveCustomerInCompany(long companyId, Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customer id is required");
        }
        String status = jdbc.query(
                "SELECT status FROM customers WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> rs.getString(1), companyId, customerId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "customer not found in this company"));
        if (!CustomerManagementService.STATUS_ACTIVE.equals(status)) {
            throw new ConflictException("customer is not active");
        }
        return customerId;
    }

    private void insertItems(long companyId, long invoiceId, List<ValidatedItem> items) {
        for (int i = 0; i < items.size(); i++) {
            ValidatedItem item = items.get(i);
            // invoice_items carries no created_by column (V2): item-level
            // attribution is provided by the invoice row and the audit event.
            jdbc.update("""
                    INSERT INTO invoice_items
                        (company_id, invoice_id, description, quantity, unit_price, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, companyId, invoiceId, item.description(), item.quantity(),
                    item.unitPrice(), i);
        }
    }

    /** Validates the raw item list; empty lists and oversized sets are refused. */
    private static List<ValidatedItem> validateItems(List<InvoiceItemRequest> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            throw new IllegalArgumentException("at least one line item is required");
        }
        if (rawItems.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    "at most " + MAX_ITEMS + " line items are allowed");
        }
        List<ValidatedItem> items = new ArrayList<>(rawItems.size());
        for (int i = 0; i < rawItems.size(); i++) {
            InvoiceItemRequest raw = rawItems.get(i);
            String label = "item " + (i + 1);
            if (raw == null) {
                throw new IllegalArgumentException(label + " is required");
            }
            String description = requireText(raw.description(),
                    label + " description is required", MAX_ITEM_DESCRIPTION,
                    label + " description");
            BigDecimal quantity = raw.quantity();
            if (quantity == null) {
                throw new IllegalArgumentException(label + " quantity is required");
            }
            if (quantity.signum() <= 0) {
                throw new IllegalArgumentException(label + " quantity must be positive");
            }
            requireScale(quantity, QUANTITY_SCALE, label + " quantity");
            if (quantity.compareTo(MAX_QUANTITY) > 0) {
                throw new IllegalArgumentException(label + " quantity is out of bounds");
            }
            BigDecimal unitPrice = raw.unitPrice();
            if (unitPrice == null) {
                throw new IllegalArgumentException(label + " unit price is required");
            }
            if (unitPrice.signum() < 0) {
                throw new IllegalArgumentException(label + " unit price must not be negative");
            }
            requireScale(unitPrice, PRICE_SCALE, label + " unit price");
            if (unitPrice.compareTo(MAX_UNIT_PRICE) > 0) {
                throw new IllegalArgumentException(label + " unit price is out of bounds");
            }
            BigDecimal lineTotal = lineTotal(quantity, unitPrice);
            if (lineTotal.compareTo(MAX_TOTAL) > 0) {
                throw new IllegalArgumentException(label + " line total is out of bounds");
            }
            items.add(new ValidatedItem(description, quantity, unitPrice));
        }
        return items;
    }

    private static void requireScale(BigDecimal value, int maxScale, String label) {
        if (value.stripTrailingZeros().scale() > maxScale) {
            throw new IllegalArgumentException(
                    label + " must have at most " + maxScale + " decimal places");
        }
    }

    /**
     * One line's total, rounded HALF_UP to 2 decimals - deliberately
     * identical to the V2 generated column
     * {@code round(quantity * unit_price, 2)} (half away from zero; both
     * operands are non-negative here, so the two rules coincide).
     * Public for the API tests that prove this equality against the
     * database's own generated values.
     */
    public static BigDecimal lineTotal(BigDecimal quantity, BigDecimal unitPrice) {
        return quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal totalOf(List<ValidatedItem> items) {
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        for (ValidatedItem item : items) {
            total = total.add(lineTotal(item.quantity(), item.unitPrice()));
            if (total.compareTo(MAX_TOTAL) > 0) {
                throw new IllegalArgumentException("invoice total is out of bounds");
            }
        }
        return total;
    }

    private static LocalDate requireDate(String raw, String blankMessage) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(blankMessage);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(normalized);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("dates must use the ISO format yyyy-MM-dd");
        }
        if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
            throw new IllegalArgumentException(
                    "dates must be between " + MIN_YEAR + " and " + MAX_YEAR);
        }
        return date;
    }

    private static void requireDateOrder(LocalDate issueDate, LocalDate dueDate) {
        if (dueDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("due date must not be before the issue date");
        }
    }

    private java.util.Optional<InvoiceSummaryDto> findSummaryInCompany(long companyId,
            long invoiceId) {
        return jdbc.query(
                """
                SELECT i.id, i.invoice_number, i.customer_id, i.issue_date, i.due_date,
                       i.currency_code, i.status, i.total_amount, i.description,
                       i.issued_at, i.voided_at, i.created_at, i.updated_at,
                       c.customer_code, c.display_name AS customer_name,
                       %s AS allocated_amount
                FROM invoices i
                JOIN customers c ON c.id = i.customer_id AND c.company_id = i.company_id
                WHERE i.company_id = ? AND i.id = ?
                """.formatted(ALLOCATED_SUM_SQL),
                (rs, rowNum) -> mapSummary(rs), companyId, invoiceId).stream().findFirst();
    }

    private java.util.Optional<InvoiceDetailDto> findDetailInCompany(long companyId,
            long invoiceId) {
        return findSummaryInCompany(companyId, invoiceId)
                .map(summary -> new InvoiceDetailDto(summary.id(), summary.invoiceNumber(),
                        summary.customerId(), summary.customerCode(), summary.customerName(),
                        summary.issueDate(), summary.dueDate(), summary.currencyCode(),
                        summary.status(), summary.totalAmount(), summary.outstandingAmount(),
                        summary.derivedStatus(), summary.description(), summary.issuedAt(),
                        summary.voidedAt(), summary.createdAt(), summary.updatedAt(), null));
    }

    private InvoiceDetailDto getInCompanyOrThrow(long companyId, long invoiceId) {
        InvoiceDetailDto invoice = findDetailInCompany(companyId, invoiceId)
                .orElseThrow(() -> new IllegalStateException(
                        "invoice missing immediately after write"));
        return withItems(companyId, invoiceId, invoice);
    }

    private static InvoiceSummaryDto mapSummary(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        String status = rs.getString("status");
        BigDecimal totalAmount = rs.getBigDecimal("total_amount");
        BigDecimal allocated = rs.getBigDecimal("allocated_amount");
        // Only ISSUED invoices carry outstanding debt (PRD FR-08).
        BigDecimal outstanding = STATUS_ISSUED.equals(status)
                ? totalAmount.subtract(allocated).max(BigDecimal.ZERO)
                : BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        String derivedStatus = deriveStatus(status, outstanding, allocated,
                rs.getDate("due_date").toLocalDate());
        return new InvoiceSummaryDto(
                rs.getLong("id"),
                rs.getString("invoice_number"),
                rs.getLong("customer_id"),
                rs.getString("customer_code"),
                rs.getString("customer_name"),
                rs.getDate("issue_date").toLocalDate(),
                rs.getDate("due_date").toLocalDate(),
                rs.getString("currency_code"),
                status,
                totalAmount,
                outstanding,
                derivedStatus,
                rs.getString("description"),
                rs.getTimestamp("issued_at") == null ? null
                        : rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("voided_at") == null ? null
                        : rs.getTimestamp("voided_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * Derived (never stored) state for an invoice. Precedence:
     * lifecycle status, then PAID, OVERDUE, PARTIALLY_PAID, UNPAID. Overdue
     * outranks partially paid (PRD FR-08: any positive balance past its due
     * date is overdue).
     */
    static String deriveStatus(String status, BigDecimal outstanding,
            BigDecimal allocated, LocalDate dueDate) {
        if (!STATUS_ISSUED.equals(status)) {
            return status;
        }
        if (outstanding.signum() <= 0) {
            return "PAID";
        }
        if (dueDate.isBefore(LocalDate.now())) {
            return "OVERDUE";
        }
        return allocated.signum() > 0 ? "PARTIALLY_PAID" : "UNPAID";
    }

    /** The authenticated actor, taken from the session security context. */
    private long currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CpdtsPrincipal principal)) {
            throw new AccessDeniedException("no authenticated actor");
        }
        return principal.userId();
    }

    private static String requireText(String value, String blankMessage, int maxLength,
            String fieldLabel) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(blankMessage);
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldLabel + " must be at most " + maxLength
                    + " characters");
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength, String fieldLabel) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldLabel + " must be at most " + maxLength
                    + " characters");
        }
        return normalized;
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void putChange(Map<String, Object> before, Map<String, Object> after,
            String field, Object oldValue, Object newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        before.put(field, oldValue == null ? "" : oldValue);
        after.put(field, newValue == null ? "" : newValue);
    }

    private static List<Object> concat(List<Object> base, Object... extra) {
        List<Object> all = new ArrayList<>(base);
        for (Object value : extra) {
            all.add(value);
        }
        return all;
    }

    /**
     * One append-only audit row in the caller's transaction - it commits or
     * rolls back exactly with the change it describes. Minimized JSON: never
     * whole rows, never free-text item dumps.
     */
    private void audit(long actorId, Long companyId, String action, Long invoiceId,
            Map<String, Object> before, Map<String, Object> after, String reason) {
        jdbc.update("""
                INSERT INTO audit_logs
                    (company_id, actor_user_id, action, entity_type, entity_id,
                     before_data, after_data, reason)
                VALUES (?, ?, ?, 'INVOICE', ?, ?::jsonb, ?::jsonb, ?)
                """,
                companyId, actorId, action, invoiceId,
                before == null ? null : objectMapper.writeValueAsString(before),
                after == null ? null : objectMapper.writeValueAsString(after),
                reason);
    }

    /** One validated line item (already within schema bounds). */
    private record ValidatedItem(String description, BigDecimal quantity,
            BigDecimal unitPrice) {
    }

    private record InvoiceRow(long id, String status, LocalDate issueDate, LocalDate dueDate,
            BigDecimal totalAmount, long customerId, String description) {
    }
}
