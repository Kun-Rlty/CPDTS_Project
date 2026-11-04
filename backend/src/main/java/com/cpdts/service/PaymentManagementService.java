package com.cpdts.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.security.Permissions;
import com.cpdts.web.ConflictException;
import com.cpdts.web.NotFoundException;
import com.cpdts.web.PaymentManagementController.AllocatePaymentRequest;
import com.cpdts.web.PaymentManagementController.AllocationDto;
import com.cpdts.web.PaymentManagementController.AllocationRequest;
import com.cpdts.web.PaymentManagementController.AllocationResult;
import com.cpdts.web.PaymentManagementController.PaymentCreateResult;
import com.cpdts.web.PaymentManagementController.PaymentDetailDto;
import com.cpdts.web.PaymentManagementController.PaymentDto;
import com.cpdts.web.PaymentManagementController.PaymentPage;
import com.cpdts.web.PaymentManagementController.RecordPaymentRequest;
import com.cpdts.web.PaymentManagementController.RejectPaymentRequest;
import com.cpdts.web.PaymentManagementController.ReversePaymentRequest;
import com.cpdts.web.PaymentManagementController.VerificationDto;
import com.cpdts.web.PaymentManagementController.VerifyPaymentRequest;

import tools.jackson.databind.ObjectMapper;

/**
 * Company-scoped payment management (PRD FR-05/FR-06/FR-07; implementation
 * plan Phase 6): record, list/detail, manual verify/reject, explicit
 * allocation to ISSUED invoices, and admin-only whole-payment reversal.
 *
 * <p>Lifecycle (V3 statuses): a recorded payment starts
 * {@code PENDING_VERIFICATION} and receives exactly one manual decision -
 * {@code VERIFIED} or {@code REJECTED} (never by its own recorder, even a
 * platform admin; the V3 trigger is the database backstop).
 * {@code REVERSED} is reachable only from VERIFIED through the audited
 * whole-payment reversal. REJECTED and REVERSED are terminal in this
 * prototype: corrections are NEW payment records; there are no financial
 * edits and no hard deletes anywhere (V3 blocks both).
 *
 * <p>Money effects: only ACTIVE allocations of VERIFIED payments reduce
 * invoice balances (recomputed on read - see
 * {@link InvoiceManagementService#ALLOCATED_SUM_SQL}); verified funds that
 * are not yet allocated remain customer credit (derived: verified amount
 * minus ACTIVE allocations). Pending, rejected and reversed payments
 * contribute NEITHER settled amounts NOR credit. Nothing is ever allocated
 * automatically - allocation is an explicit, permission-checked request.
 *
 * <p>Row-lock ordering (consistent everywhere; documented contract):
 * <ul>
 *   <li>verify / reject / allocate / reverse: lock the PAYMENT row
 *       {@code FOR UPDATE} first, re-check its status AFTER the lock;</li>
 *   <li>allocate then locks the target INVOICE rows {@code FOR UPDATE} in
 *       ASCENDING invoice-id order (a single global order - two concurrent
 *       allocations can never deadlock), re-checks each invoice is ISSUED
 *       and recomputes credit and outstanding AFTER locking;</li>
 *   <li>invoice voiding (invoice service) locks only the invoice row.</li>
 * </ul>
 * No operation ever locks an invoice before a payment, so the lock graph is
 * acyclic. The payment-first order is also what makes reversal-versus-
 * allocation and void-versus-allocation serialize into single winners, with
 * the V3/V7 triggers as database-level backstops.
 *
 * <p>Idempotency: POST /payments and POST /payments/{id}/allocations
 * REQUIRE an {@code Idempotency-Key} header. The key row is inserted in the
 * SAME transaction as the mutation, guarded by
 * {@code UNIQUE (company_id, scope, key)} - a duplicate commit is physically
 * impossible. A retry with the same key and the SAME request replays the
 * original entity (HTTP 200 for creation replays); the same key with a
 * DIFFERENT request is rejected with 409. A lost insert race is caught and
 * resolved through the same replay path.
 *
 * <p>Audit: PAYMENT_RECORDED / PAYMENT_VERIFIED / PAYMENT_REJECTED /
 * PAYMENT_ALLOCATED / PAYMENT_REVERSED rows are written in the SAME
 * transaction as the change with the acting session user as actor and
 * minimized JSON data (rejection/reversal reasons go to audit_logs.reason).
 */
@Service
public class PaymentManagementService {

    static final String STATUS_PENDING = "PENDING_VERIFICATION";
    static final String STATUS_VERIFIED = "VERIFIED";
    static final String STATUS_REJECTED = "REJECTED";
    static final String STATUS_REVERSED = "REVERSED";
    static final String ALLOCATION_ACTIVE = "ACTIVE";
    static final String SEQUENCE_KIND = "PAYMENT";
    static final String CURRENCY_TZS = "TZS";

    static final String METHOD_CASH = "CASH";
    static final String METHOD_BANK_TRANSFER = "BANK_TRANSFER";
    static final String METHOD_MOBILE_MONEY = "MOBILE_MONEY";

    static final String SCOPE_CREATE = "PAYMENT_CREATE";
    static final String SCOPE_ALLOCATE = "PAYMENT_ALLOCATE";

    /** unit_price/amount NUMERIC(14,2) bounds. */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    static final int MONEY_SCALE = 2;
    static final int MAX_REFERENCE = 100;
    static final int MAX_NOTES = 1000;
    /** Engineering bound on the unbounded TEXT reason columns. */
    static final int MAX_REASON = 10_000;
    static final int MAX_ALLOCATION_LINES = 100;
    static final int MAX_IDEMPOTENCY_KEY = 64;
    /** Engineering date guard (not a business policy): 2000..2100. */
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    /** Business clock: its zone (Africa/Dar_es_Salaam) defines business dates. */
    private final Clock clock;

    public PaymentManagementService(JdbcTemplate jdbc,
            AccessControlService accessControlService, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Record (PAYMENT_RECORD + Idempotency-Key)
    // ------------------------------------------------------------------

    /**
     * Records a PENDING_VERIFICATION payment. The payment number comes from
     * the company's PAYMENT sequence (locked inside the creation
     * transaction), the recorder/company/currency/status are server-derived,
     * and client-supplied number/status/recordedBy fields are never read.
     */
    public PaymentCreateResult recordPayment(long companyId, RecordPaymentRequest request,
            String idempotencyKey) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.PAYMENT_RECORD);

        String key = requireIdempotencyKey(idempotencyKey);
        String scope = SCOPE_CREATE;
        String hash = hashCreateRequest(request);
        ExistingKey existing = findKey(companyId, scope, key);
        if (existing != null) {
            return new PaymentCreateResult(replayDetail(existing, hash, companyId, actorId, key), true);
        }
        try {
            PaymentDetailDto created = transactionTemplate.execute(
                    status -> doRecordPayment(companyId, actorId, request, key, scope, hash));
            return new PaymentCreateResult(created, false);
        } catch (DataIntegrityViolationException ex) {
            // A concurrent identical request won the key race; replay its result.
            ExistingKey raced = findKey(companyId, scope, key);
            if (raced != null) {
                return new PaymentCreateResult(replayDetail(raced, hash, companyId, actorId, key), true);
            }
            throw ex;
        }
    }

    private PaymentDetailDto doRecordPayment(long companyId, long actorId,
            RecordPaymentRequest request, String key, String scope, String hash) {
        long customerId = requireCustomerInCompany(companyId,
                request == null ? null : request.customerId());
        BigDecimal amount = requireMoney(request == null ? null : request.amount(),
                "amount is required");
        String method = requireMethod(request == null ? null : request.method());
        Instant receivedAt = requireReceivedAt(
                request == null ? null : request.receivedAt());
        String referenceNumber = optionalText(
                request == null ? null : referenceOf(request), MAX_REFERENCE,
                "reference number");
        String notes = optionalText(request == null ? null : request.notes(), MAX_NOTES, "notes");

        Long sequence = jdbc.queryForObject("""
                INSERT INTO number_sequences (company_id, kind, next_value)
                VALUES (?, ?, 2)
                ON CONFLICT (company_id, kind) DO UPDATE
                SET next_value = number_sequences.next_value + 1, updated_at = now()
                RETURNING next_value - 1
                """, Long.class, companyId, SEQUENCE_KIND);
        if (sequence == null) {
            throw new IllegalStateException("payment sequence returned no value");
        }
        String companyCode = jdbc.queryForObject(
                "SELECT code FROM companies WHERE id = ?", String.class, companyId);
        String paymentNumber = "%s-PAY-%05d".formatted(companyCode, sequence);

        Long paymentId = jdbc.queryForObject("""
                INSERT INTO payments
                    (company_id, customer_id, payment_number, amount, currency_code, method,
                     reference_number, received_at, status, recorded_by, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, companyId, customerId, paymentNumber, amount, CURRENCY_TZS,
                method, referenceNumber, Timestamp.from(receivedAt), STATUS_PENDING, actorId,
                notes);
        if (paymentId == null) {
            throw new IllegalStateException("payment insert returned no id");
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("payment_number", paymentNumber);
        after.put("customer_id", customerId);
        after.put("amount", amount.toPlainString());
        after.put("method", method);
        after.put("status", STATUS_PENDING);
        audit(actorId, companyId, "PAYMENT_RECORDED", paymentId, null, after, null);

        insertKey(companyId, actorId, scope, key, hash, "PAYMENT", paymentId);
        return getInCompanyOrThrow(companyId, paymentId);
    }

    // ------------------------------------------------------------------
    // Verify / reject (PAYMENT_VERIFY; recorder never decides)
    // ------------------------------------------------------------------

    /**
     * Manual decision VERIFIED on a PENDING payment. The note is optional
     * and stored in the append-only verification history. The recorder of
     * the payment can NEVER decide it - enforced here for everyone
     * (including platform admins) and again by the V3 trigger.
     */
    public PaymentDetailDto verifyPayment(long companyId, long paymentId,
            VerifyPaymentRequest request) {
        return decide(companyId, paymentId, STATUS_VERIFIED,
                request == null ? null : request.notes(), false, "PAYMENT_VERIFIED");
    }

    /** Manual decision REJECTED; the reason is mandatory (V3 constraint). */
    public PaymentDetailDto rejectPayment(long companyId, long paymentId,
            RejectPaymentRequest request) {
        return decide(companyId, paymentId, STATUS_REJECTED,
                request == null ? null : request.reason(), true, "PAYMENT_REJECTED");
    }

    /**
     * One manual decision on a PENDING payment. Authorization (PAYMENT_VERIFY)
     * is checked FIRST, exactly like the invoice service: an unauthorized
     * caller must get 403 regardless of how valid their payload is. The
     * reason is validated only after that (mandatory for REJECTED - V3
     * ck_payment_verifications_reason; optional bounded notes for VERIFIED).
     */
    private PaymentDetailDto decide(long companyId, long paymentId, String decision,
            String rawReason, boolean reasonRequired, String auditAction) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.PAYMENT_VERIFY);

        String reason = reasonRequired
                ? requireText(rawReason, "rejection reason is required", MAX_REASON,
                        "rejection reason")
                : optionalText(rawReason, MAX_REASON, "verification notes");

        return transactionTemplate.execute(txStatus -> {
            PaymentRow payment = lockPayment(companyId, paymentId);
            if (!STATUS_PENDING.equals(payment.status())) {
                throw new ConflictException("only PENDING payments can receive a decision");
            }
            if (payment.recordedBy() == actorId) {
                throw new AccessDeniedException(
                        "the recorder of a payment can never verify or reject it");
            }
            jdbc.update("""
                    INSERT INTO payment_verifications
                        (company_id, payment_id, verifier_id, decision, reason)
                    VALUES (?, ?, ?, ?, ?)
                    """, companyId, paymentId, actorId, decision, reason);
            jdbc.update("""
                    UPDATE payments SET status = ?, updated_at = now()
                    WHERE company_id = ? AND id = ?
                    """, decision, companyId, paymentId);

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("payment_number", payment.paymentNumber());
            after.put("status", decision);
            audit(actorId, companyId, auditAction, paymentId,
                    Map.of("status", STATUS_PENDING), after, reason);
            return getInCompanyOrThrow(companyId, paymentId);
        });
    }

    // ------------------------------------------------------------------
    // Allocate (PAYMENT_RECORD + Idempotency-Key)
    // ------------------------------------------------------------------

    /**
     * Explicitly allocates a VERIFIED payment to ISSUED invoices of the SAME
     * customer and company, in one transaction, under the documented lock
     * order (payment, then invoices ASC). Partial and multi-invoice
     * allocations are supported; requests never exceed the payment's
     * available credit nor any invoice's outstanding balance, and each
     * invoice may appear at most once per request.
     */
    public AllocationResult allocatePayment(long companyId, long paymentId,
            AllocatePaymentRequest request, String idempotencyKey) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.PAYMENT_RECORD);

        String key = requireIdempotencyKey(idempotencyKey);
        String scope = SCOPE_ALLOCATE;
        String hash = hashAllocateRequest(request);
        ExistingKey existing = findKey(companyId, scope, key);
        if (existing != null) {
            return new AllocationResult(replayDetail(existing, hash, companyId, actorId, key), true);
        }
        try {
            PaymentDetailDto detail = transactionTemplate.execute(
                    status -> doAllocate(companyId, paymentId, actorId, request, key, scope,
                            hash));
            return new AllocationResult(detail, false);
        } catch (DataIntegrityViolationException ex) {
            ExistingKey raced = findKey(companyId, scope, key);
            if (raced != null) {
                return new AllocationResult(replayDetail(raced, hash, companyId, actorId, key), true);
            }
            throw ex;
        }
    }

    private PaymentDetailDto doAllocate(long companyId, long paymentId, long actorId,
            AllocatePaymentRequest request, String key, String scope, String hash) {
        List<AllocationRequest> lines = validateAllocationRequest(request);
        // Claim the idempotency key FIRST (fail fast on a concurrent twin).
        insertKey(companyId, actorId, scope, key, hash, "PAYMENT", paymentId);

        // 1) Lock the payment; re-check status AFTER the lock.
        PaymentRow payment = lockPayment(companyId, paymentId);
        if (!STATUS_VERIFIED.equals(payment.status())) {
            throw new ConflictException("only VERIFIED payments can be allocated");
        }
        BigDecimal allocatedSoFar = activeAllocationSum(companyId, paymentId);
        BigDecimal credit = payment.amount().subtract(allocatedSoFar).max(BigDecimal.ZERO);
        BigDecimal requested = lines.stream().map(AllocationRequest::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (requested.compareTo(credit) > 0) {
            throw new IllegalArgumentException(
                    "allocation exceeds the available payment credit of " + credit.toPlainString());
        }

        // 2) Lock the invoices in ASCENDING id order (global order - no
        //    deadlocks), re-check each is ISSUED and recompute outstanding.
        BigDecimal totalAllocated = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        for (AllocationRequest line : lines.stream()
                .sorted(Comparator.comparing(AllocationRequest::invoiceId)).toList()) {
            InvoiceRow invoice = lockInvoice(companyId, line.invoiceId());
            // Same-customer rule in the SERVICE (the V3 composite FK is the
            // backstop): a same-company invoice of ANOTHER customer must come
            // back as 404, never as an internal FK failure.
            if (invoice.customerId() != payment.customerId()) {
                throw new NotFoundException(
                        "invoice not found in this company for this customer");
            }
            if (!InvoiceManagementService.STATUS_ISSUED.equals(invoice.status())) {
                throw new ConflictException("only ISSUED invoices can receive allocations");
            }
            BigDecimal outstanding = invoice.totalAmount()
                    .subtract(invoiceAllocatedSum(companyId, invoice.id())).max(BigDecimal.ZERO);
            if (line.amount().compareTo(outstanding) > 0) {
                throw new IllegalArgumentException(
                        "allocation exceeds the outstanding balance of invoice "
                                + line.invoiceId() + " (" + outstanding.toPlainString() + ")");
            }
            jdbc.update("""
                    INSERT INTO payment_allocations
                        (company_id, customer_id, payment_id, invoice_id, amount, status,
                         created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, companyId, payment.customerId(), paymentId, invoice.id(),
                    line.amount(), ALLOCATION_ACTIVE, actorId);
            totalAllocated = totalAllocated.add(line.amount());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("payment_number", payment.paymentNumber());
        after.put("allocation_count", lines.size());
        after.put("total_allocated", totalAllocated.toPlainString());
        audit(actorId, companyId, "PAYMENT_ALLOCATED", paymentId, null, after, null);
        return getInCompanyOrThrow(companyId, paymentId);
    }

    // ------------------------------------------------------------------
    // Reverse (PAYMENT_REVERSE - admin only)
    // ------------------------------------------------------------------

    /**
     * Whole-payment reversal for platform/company admins (V8 PAYMENT_REVERSE
     * permission). Only VERIFIED payments can be reversed; the payment and
     * ALL of its ACTIVE allocations are reversed atomically in one
     * transaction, which restores every affected invoice's derived balance.
     * Everything is preserved: verification history, allocation rows and
     * payment row remain with full reason/actor/timestamp.
     */
    public PaymentDetailDto reversePayment(long companyId, long paymentId,
            ReversePaymentRequest request) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.PAYMENT_REVERSE);

        String reason = requireText(request == null ? null : request.reason(),
                "reversal reason is required", MAX_REASON, "reversal reason");

        return transactionTemplate.execute(txStatus -> {
            PaymentRow payment = lockPayment(companyId, paymentId);
            if (!STATUS_VERIFIED.equals(payment.status())) {
                throw new ConflictException("only VERIFIED payments can be reversed");
            }
            int reversed = jdbc.update("""
                    UPDATE payment_allocations
                    SET status = 'REVERSED', reversed_at = now(), reversed_by = ?,
                        reversal_reason = ?
                    WHERE company_id = ? AND payment_id = ? AND status = 'ACTIVE'
                    """, actorId, reason, companyId, paymentId);
            jdbc.update("""
                    UPDATE payments
                    SET status = 'REVERSED', reversed_at = now(), reversed_by = ?,
                        reversal_reason = ?, updated_at = now()
                    WHERE company_id = ? AND id = ?
                    """, actorId, reason, companyId, paymentId);

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("payment_number", payment.paymentNumber());
            after.put("status", STATUS_REVERSED);
            after.put("reversed_allocations", reversed);
            audit(actorId, companyId, "PAYMENT_REVERSED", paymentId,
                    Map.of("status", STATUS_VERIFIED), after, reason);
            return getInCompanyOrThrow(companyId, paymentId);
        });
    }

    // ------------------------------------------------------------------
    // Reads (RECORD_VIEW)
    // ------------------------------------------------------------------

    /** One page of the company's payments with derived amounts. */
    public PaymentPage listPayments(long companyId, Integer page, Integer size, String q,
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
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isEmpty() && !STATUS_PENDING.equals(normalizedStatus)
                && !STATUS_VERIFIED.equals(normalizedStatus)
                && !STATUS_REJECTED.equals(normalizedStatus)
                && !STATUS_REVERSED.equals(normalizedStatus)) {
            throw new IllegalArgumentException("status must be PENDING_VERIFICATION, VERIFIED, "
                    + "REJECTED or REVERSED");
        }
        String search = q == null ? "" : q.trim();
        String searchPattern = search.isEmpty() ? null : "%" + escapeLike(search) + "%";

        StringBuilder where = new StringBuilder(" WHERE p.company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        if (!normalizedStatus.isEmpty()) {
            where.append(" AND p.status = ?");
            args.add(normalizedStatus);
        }
        if (customerId != null) {
            // Scoped by company as well: a foreign customer id matches nothing.
            where.append(" AND p.customer_id = ?");
            args.add(customerId);
        }
        if (searchPattern != null) {
            where.append("""
                     AND (p.payment_number    ILIKE ? ESCAPE '\\'
                       OR p.reference_number  ILIKE ? ESCAPE '\\'
                       OR p.notes             ILIKE ? ESCAPE '\\')
                    """);
            args.add(searchPattern);
            args.add(searchPattern);
            args.add(searchPattern);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM payments p " + where,
                Long.class, args.toArray());
        List<PaymentDto> items = jdbc.query(
                (PAYMENT_SELECT + where + " ORDER BY p.received_at DESC, p.id DESC "
                        + "LIMIT ? OFFSET ?"),
                (rs, rowNum) -> mapPayment(rs),
                concat(args, pageSize, (long) pageNumber * pageSize).toArray());

        long totalElements = total == null ? 0 : total;
        int totalPages = (int) ((totalElements + pageSize - 1) / pageSize);
        return new PaymentPage(items, pageNumber, pageSize, totalElements, totalPages);
    }

    /** Full payment (history + allocations) inside the company, or 404. */
    public PaymentDetailDto getPayment(long companyId, long paymentId) {
        accessControlService.requirePermission(currentActorId(), companyId,
                Permissions.RECORD_VIEW);
        return getInCompanyOrThrow(companyId, paymentId);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Sum of one payment's ACTIVE allocations (its consumed credit),
     * correlated on the payment row alias {@code p} of {@link #PAYMENT_SELECT}.
     * Distinct from {@link InvoiceManagementService#ALLOCATED_SUM_SQL}, which
     * is the INVOICE-side rule (correlated on {@code i}) and stays the single
     * authority for invoice outstanding balances.
     */
    static final String PAYMENT_ALLOCATED_SUM_SQL = """
            (SELECT COALESCE(SUM(a.amount), 0)
             FROM payment_allocations a
             WHERE a.payment_id = p.id
               AND a.company_id = p.company_id
               AND a.status = 'ACTIVE')""";

    static final String PAYMENT_SELECT = """
            SELECT p.id, p.payment_number, p.customer_id, p.amount, p.currency_code, p.method,
                   p.reference_number, p.received_at, p.recorded_at, p.status, p.recorded_by,
                   p.notes, p.reversed_at, p.reversed_by, p.reversal_reason,
                   p.created_at, p.updated_at,
                   c.customer_code, c.display_name AS customer_name,
                   u.full_name AS recorded_by_name,
                   %s AS allocated_amount
            FROM payments p
            JOIN customers c ON c.id = p.customer_id AND c.company_id = p.company_id
            LEFT JOIN users u ON u.id = p.recorded_by
            """.formatted(PAYMENT_ALLOCATED_SUM_SQL);

    /** Locks one payment row FOR UPDATE within the caller's transaction. */
    private PaymentRow lockPayment(long companyId, long paymentId) {
        return jdbc.query("""
                SELECT id, status, amount, customer_id, payment_number, recorded_by
                FROM payments
                WHERE company_id = ? AND id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new PaymentRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBigDecimal("amount"),
                        rs.getLong("customer_id"),
                        rs.getString("payment_number"),
                        rs.getLong("recorded_by")),
                companyId, paymentId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("payment not found in this company"));
    }

    /** Locks one invoice row FOR UPDATE within the caller's transaction. */
    private InvoiceRow lockInvoice(long companyId, long invoiceId) {
        return jdbc.query("""
                SELECT id, status, total_amount, customer_id
                FROM invoices
                WHERE company_id = ? AND id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new InvoiceRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getBigDecimal("total_amount"),
                        rs.getLong("customer_id")),
                companyId, invoiceId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "invoice not found in this company for this customer"));
    }

    /** Sum of the payment's own ACTIVE allocations (its credit consumption). */
    private BigDecimal activeAllocationSum(long companyId, long paymentId) {
        BigDecimal sum = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount), 0) FROM payment_allocations
                WHERE company_id = ? AND payment_id = ? AND status = 'ACTIVE'
                """, BigDecimal.class, companyId, paymentId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** An invoice's allocated sum - the invoice service's single rule. */
    private BigDecimal invoiceAllocatedSum(long companyId, long invoiceId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT " + InvoiceManagementService.ALLOCATED_SUM_SQL
                        + " FROM invoices i WHERE i.company_id = ? AND i.id = ?",
                BigDecimal.class, companyId, invoiceId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private PaymentDetailDto getInCompanyOrThrow(long companyId, long paymentId) {
        PaymentDto payment = jdbc.query(PAYMENT_SELECT + " WHERE p.company_id = ? AND p.id = ?",
                (rs, rowNum) -> mapPayment(rs), companyId, paymentId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "payment not found in this company"));
        List<VerificationDto> verifications = jdbc.query("""
                SELECT v.id, v.decision, v.reason, v.verifier_id, v.created_at,
                       u.full_name AS verifier_name
                FROM payment_verifications v
                LEFT JOIN users u ON u.id = v.verifier_id
                WHERE v.company_id = ? AND v.payment_id = ?
                ORDER BY v.created_at, v.id
                """, (rs, rowNum) -> new VerificationDto(
                        rs.getLong("id"),
                        rs.getString("decision"),
                        rs.getString("reason"),
                        rs.getLong("verifier_id"),
                        rs.getString("verifier_name"),
                        rs.getTimestamp("created_at").toInstant()),
                companyId, paymentId);
        List<AllocationDto> allocations = jdbc.query("""
                SELECT a.id, a.invoice_id, a.amount, a.status, a.created_at, a.reversed_at,
                       a.reversal_reason, i.invoice_number
                FROM payment_allocations a
                LEFT JOIN invoices i ON i.id = a.invoice_id AND i.company_id = a.company_id
                WHERE a.company_id = ? AND a.payment_id = ?
                ORDER BY a.created_at, a.id
                """, (rs, rowNum) -> new AllocationDto(
                        rs.getLong("id"),
                        rs.getLong("invoice_id"),
                        rs.getString("invoice_number"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("reversed_at") == null ? null
                                : rs.getTimestamp("reversed_at").toInstant(),
                        rs.getString("reversal_reason")),
                companyId, paymentId);
        return new PaymentDetailDto(payment, verifications, allocations);
    }

    static PaymentDto mapPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        String status = rs.getString("status");
        BigDecimal amount = rs.getBigDecimal("amount");
        BigDecimal allocated = rs.getBigDecimal("allocated_amount");
        // Money always renders with 2 decimals: the zero case comes back from
        // COALESCE as scale 0 ("0"), so normalize UP (never rounding - NUMERIC(14,2)
        // sums can only have scale 0 or 2).
        if (allocated != null && allocated.scale() < MONEY_SCALE) {
            allocated = allocated.setScale(MONEY_SCALE);
        }
        // Only VERIFIED payments carry credit; everyone else is zero.
        BigDecimal credit = STATUS_VERIFIED.equals(status)
                ? amount.subtract(allocated).max(BigDecimal.ZERO)
                : BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        return new PaymentDto(
                rs.getLong("id"),
                rs.getString("payment_number"),
                rs.getLong("customer_id"),
                rs.getString("customer_code"),
                rs.getString("customer_name"),
                amount,
                rs.getString("currency_code"),
                rs.getString("method"),
                rs.getString("reference_number"),
                rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("recorded_at").toInstant(),
                status,
                rs.getLong("recorded_by"),
                rs.getString("recorded_by_name"),
                rs.getString("notes"),
                allocated,
                credit,
                rs.getTimestamp("reversed_at") == null ? null
                        : rs.getTimestamp("reversed_at").toInstant(),
                rs.getObject("reversed_by") == null ? null : rs.getLong("reversed_by"),
                rs.getString("reversal_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    private record ExistingKey(long entityId, String requestHash, long actorId) {
    }

    private ExistingKey findKey(long companyId, String scope, String key) {
        return jdbc.query("""
                SELECT entity_id, request_hash, actor_user_id FROM idempotency_keys
                WHERE company_id = ? AND scope = ? AND idempotency_key = ?
                """, (rs, rowNum) -> new ExistingKey(rs.getLong("entity_id"),
                        rs.getString("request_hash"), rs.getLong("actor_user_id")),
                companyId, scope, key).stream().findFirst().orElse(null);
    }

    private void insertKey(long companyId, long actorId, String scope, String key, String hash,
            String entityType, long entityId) {
        jdbc.update("""
                INSERT INTO idempotency_keys
                    (company_id, actor_user_id, scope, idempotency_key, request_hash,
                     entity_type, entity_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, companyId, actorId, scope, key, hash, entityType, entityId);
    }

    /** Replay or reject, depending on whether the stored request matches. */
    private PaymentDetailDto replayDetail(ExistingKey existing, String hash, long companyId,
            long actorId, String key) {
        if (existing.actorId() != actorId) {
            throw new AccessDeniedException("idempotency key belongs to another actor");
        }
        if (!existing.requestHash().equals(hash)) {
            throw new ConflictException(
                    "idempotency key was already used with a different request: " + key);
        }
        return getInCompanyOrThrow(companyId, existing.entityId());
    }

    private static String requireIdempotencyKey(String key) {
        String normalized = key == null ? "" : key.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header is required for payment recording and allocation");
        }
        if (normalized.length() > MAX_IDEMPOTENCY_KEY
                || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY
                    + " characters of [A-Za-z0-9._-]");
        }
        return normalized;
    }

    /**
     * SHA-256 over a canonical rendering of the request, normalized EXACTLY
     * like the service stores it: money as a plain BigDecimal value (100.00
     * and 100.0 are the SAME request), method upper-cased, text trimmed
     * (blank = absent). A retry that resends the same request therefore
     * replays; only a genuinely different payload trips the 409.
     */
    private static String hashCreateRequest(RecordPaymentRequest request) {
        if (request == null) {
            request = new RecordPaymentRequest(null, null, null, null, null, null);
        }
        return sha256("customerId=" + request.customerId()
                + "\namount=" + (request.amount() == null ? "" : canonicalMoney(request.amount()))
                + "\nmethod=" + canonicalMethod(request.method())
                + "\nreceivedAt=" + canonicalText(request.receivedAt())
                + "\nreferenceNumber=" + canonicalText(referenceOf(request))
                + "\nnotes=" + canonicalText(request.notes()));
    }

    private static String hashAllocateRequest(AllocatePaymentRequest request) {
        StringBuilder canonical = new StringBuilder("allocations=");
        if (request != null && request.allocations() != null) {
            for (AllocationRequest line : request.allocations()) {
                canonical.append("\ninvoiceId=").append(line == null ? "" : line.invoiceId())
                        .append("|amount=").append(line == null || line.amount() == null ? ""
                                : canonicalMoney(line.amount()));
            }
        }
        return sha256(canonical.toString());
    }

    /** Money identity by VALUE, not by literal (100.00 == 100.0). */
    private static String canonicalMoney(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String canonicalMethod(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** Text identity as stored: trimmed, blank = absent. */
    private static String canonicalText(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(
                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private static List<AllocationRequest> validateAllocationRequest(
            AllocatePaymentRequest request) {
        List<AllocationRequest> lines =
                request == null ? null : request.allocations();
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one allocation line is required");
        }
        if (lines.size() > MAX_ALLOCATION_LINES) {
            throw new IllegalArgumentException(
                    "at most " + MAX_ALLOCATION_LINES + " allocation lines are allowed");
        }
        List<AllocationRequest> validated = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            AllocationRequest line = lines.get(i);
            String label = "allocation " + (i + 1);
            if (line == null || line.invoiceId() == null) {
                throw new IllegalArgumentException(label + " invoice id is required");
            }
            BigDecimal amount = requireMoney(line == null ? null : line.amount(),
                    label + " amount is required");
            validated.add(new AllocationRequest(line.invoiceId(), amount));
        }
        long distinct = validated.stream().map(AllocationRequest::invoiceId).distinct().count();
        if (distinct != validated.size()) {
            throw new IllegalArgumentException(
                    "each invoice may appear at most once per allocation request");
        }
        return validated;
    }

    /** Positive, at most 2 decimals (REJECTED, never rounded), within NUMERIC(14,2). */
    private static BigDecimal requireMoney(BigDecimal value, String blankMessage) {
        if (value == null) {
            throw new IllegalArgumentException(blankMessage);
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(
                    "amount must have at most " + MONEY_SCALE + " decimal places");
        }
        if (value.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("amount is out of bounds");
        }
        return value;
    }

    private static String requireMethod(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("method is required");
        }
        if (!METHOD_CASH.equals(normalized) && !METHOD_BANK_TRANSFER.equals(normalized)
                && !METHOD_MOBILE_MONEY.equals(normalized)) {
            throw new IllegalArgumentException(
                    "method must be CASH, BANK_TRANSFER or MOBILE_MONEY");
        }
        return normalized;
    }

    /**
     * ISO date (yyyy-MM-dd) or full ISO-8601 timestamp.
     *
     * <p>Date-only input is stored at the START of that date in the BUSINESS
     * timezone (the injected clock's zone: Africa/Dar_es_Salaam, UTC+3) -
     * never UTC, so "2026-05-10" means 2026-05-10T00:00+03:00 =
     * 2026-05-09T21:00:00Z. A full timestamp is an exact instant and is
     * stored unchanged (historical instants are never rewritten); only its
     * year guard is evaluated on the business date.
     */
    private Instant requireReceivedAt(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("received date is required");
        }
        LocalDate date;
        try {
            if (normalized.matches("\\d{4}-\\d{2}-\\d{2}")) {
                date = LocalDate.parse(normalized);
                if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
                    throw new IllegalArgumentException(
                            "received date must be between " + MIN_YEAR + " and " + MAX_YEAR);
                }
                return date.atStartOfDay(clock.getZone()).toInstant();
            }
            Instant instant = Instant.parse(normalized);
            date = java.time.LocalDateTime.ofInstant(instant, clock.getZone())
                    .toLocalDate();
            if (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR) {
                throw new IllegalArgumentException(
                        "received date must be between " + MIN_YEAR + " and " + MAX_YEAR);
            }
            return instant;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "received date must use yyyy-MM-dd or ISO-8601 timestamp format");
        }
    }

    private long requireCustomerInCompany(long companyId, Long customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("customer id is required");
        }
        // Customer status is deliberately NOT checked: collecting money from
        // a since-deactivated customer stays possible (historical access),
        // matching the invoice phase's preservation rule. Documented.
        Boolean exists = jdbc.query("SELECT TRUE FROM customers WHERE company_id = ? AND id = ?",
                (rs, rowNum) -> rs.getBoolean(1), companyId, customerId).stream().findFirst()
                .orElse(null);
        if (!Boolean.TRUE.equals(exists)) {
            throw new NotFoundException("customer not found in this company");
        }
        return customerId;
    }

    private static String referenceOf(RecordPaymentRequest request) {
        return request.referenceNumber();
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

    /** The authenticated actor, taken from the session security context. */
    private static long currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CpdtsPrincipal principal)) {
            throw new AccessDeniedException("no authenticated actor");
        }
        return principal.userId();
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
     * whole rows, never free text.
     */
    private void audit(long actorId, Long companyId, String action, Long entityId,
            Map<String, Object> before, Map<String, Object> after, String reason) {
        jdbc.update("""
                INSERT INTO audit_logs
                    (company_id, actor_user_id, action, entity_type, entity_id,
                     before_data, after_data, reason)
                VALUES (?, ?, ?, 'PAYMENT', ?, ?::jsonb, ?::jsonb, ?)
                """,
                companyId, actorId, action, entityId,
                before == null ? null : objectMapper.writeValueAsString(before),
                after == null ? null : objectMapper.writeValueAsString(after),
                reason);
    }

    /** One locked payment row. */
    private record PaymentRow(long id, String status, BigDecimal amount, long customerId,
            String paymentNumber, long recordedBy) {
    }

    /** One locked invoice row (allocation target). */
    private record InvoiceRow(long id, String status, BigDecimal totalAmount, long customerId) {
    }
}
