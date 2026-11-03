package com.cpdts.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cpdts.service.PaymentManagementService;

/**
 * Company-scoped payment endpoints (PRD FR-05/FR-06/FR-07). No PUT/PATCH/DELETE
 * routes exist on payments or allocations: verification is a manual decision
 * on a PENDING payment, corrections are reversals, and the V3 database
 * triggers block payment/verification/allocation hard deletes regardless.
 *
 * <ul>
 *   <li>POST  /api/v1/companies/{companyId}/payments - record a payment as
 *       PENDING_VERIFICATION (PAYMENT_RECORD; Idempotency-Key header
 *       REQUIRED - retries replay, key reuse with different input is 409)</li>
 *   <li>GET   /api/v1/companies/{companyId}/payments - bounded paginated
 *       list with derived allocated/credit amounts (RECORD_VIEW)</li>
 *   <li>GET   /api/v1/companies/{companyId}/payments/{paymentId} - detail
 *       with verification history and allocations (RECORD_VIEW)</li>
 *   <li>POST  /api/v1/companies/{companyId}/payments/{paymentId}/verify -
 *       PENDING -&gt; VERIFIED (PAYMENT_VERIFY; recorder can NEVER decide,
 *       including platform admins)</li>
 *   <li>POST  /api/v1/companies/{companyId}/payments/{paymentId}/reject -
 *       PENDING -&gt; REJECTED with a mandatory reason (PAYMENT_VERIFY)</li>
 *   <li>POST  /api/v1/companies/{companyId}/payments/{paymentId}/allocations
 *       - allocate a VERIFIED payment to ISSUED invoices of the same
 *       customer (PAYMENT_RECORD; Idempotency-Key header REQUIRED; partial
 *       and multi-invoice supported; never beyond credit or outstanding)</li>
 *   <li>POST  /api/v1/companies/{companyId}/payments/{paymentId}/reverse -
 *       whole-payment reversal (PAYMENT_REVERSE - admin only; mandatory
 *       reason; reverses the payment and its ACTIVE allocations atomically)</li>
 * </ul>
 *
 * <p>Authorization, company scoping, lifecycle rules, numbering, idempotency
 * and audit live in {@link PaymentManagementService}. Request DTOs contain NO
 * id/number/status/company/currency/recordedBy fields: recorder identity,
 * company ownership, payment numbers, currency (TZS) and the initial
 * PENDING_VERIFICATION status are always derived server-side.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/payments")
public class PaymentManagementController {

    private final PaymentManagementService service;

    public PaymentManagementController(PaymentManagementService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentDetailDto> create(@PathVariable long companyId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody RecordPaymentRequest body) {
        PaymentCreateResult result = service.recordPayment(companyId, body, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.payment());
    }

    @GetMapping
    public PaymentPage list(@PathVariable long companyId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId) {
        return service.listPayments(companyId, page, size, q, status, customerId);
    }

    @GetMapping("/{paymentId}")
    public PaymentDetailDto detail(@PathVariable long companyId,
            @PathVariable long paymentId) {
        return service.getPayment(companyId, paymentId);
    }

    @PostMapping("/{paymentId}/verify")
    public PaymentDetailDto verify(@PathVariable long companyId,
            @PathVariable long paymentId, @RequestBody(required = false) VerifyPaymentRequest body) {
        return service.verifyPayment(companyId, paymentId, body);
    }

    @PostMapping("/{paymentId}/reject")
    public PaymentDetailDto reject(@PathVariable long companyId,
            @PathVariable long paymentId, @RequestBody RejectPaymentRequest body) {
        return service.rejectPayment(companyId, paymentId, body);
    }

    @PostMapping("/{paymentId}/allocations")
    public PaymentDetailDto allocate(@PathVariable long companyId,
            @PathVariable long paymentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AllocatePaymentRequest body) {
        AllocationResult result = service.allocatePayment(companyId, paymentId, body,
                idempotencyKey);
        return result.payment();
    }

    @PostMapping("/{paymentId}/reverse")
    public PaymentDetailDto reverse(@PathVariable long companyId,
            @PathVariable long paymentId, @RequestBody ReversePaymentRequest body) {
        return service.reversePayment(companyId, paymentId, body);
    }

    // ------------------------------------------------------------------
    // Request/response DTOs
    // ------------------------------------------------------------------

    /**
     * amount is NUMERIC(14,2): positive, at most 2 decimals (extra precision
     * is REJECTED, never silently rounded), currency always TZS. method is
     * CASH / BANK_TRANSFER / MOBILE_MONEY. receivedAt is an ISO date
     * (yyyy-MM-dd) or full ISO-8601 timestamp - the business date money
     * physically changed hands. referenceNumber is optional (cash may have
     * none - PRD FR-05); duplicate references are NOT blocked (policy still
     * open per PRD; the supporting index exists for future warnings).
     */
    public record RecordPaymentRequest(Long customerId, BigDecimal amount, String method,
            String receivedAt, String referenceNumber, String notes) {
    }

    /** Verification note is optional and stored in the decision history. */
    public record VerifyPaymentRequest(String notes) {
    }

    /** Rejection reason is mandatory (V3 ck_payment_verifications_reason). */
    public record RejectPaymentRequest(String reason) {
    }

    /** One allocation line: ISSUED invoice of the SAME customer/company. */
    public record AllocationRequest(Long invoiceId, BigDecimal amount) {
    }

    /** 1..100 lines; each invoice at most once per request. */
    public record AllocatePaymentRequest(List<AllocationRequest> allocations) {
    }

    /** Whole-payment reversal; reason is mandatory (V3 constraints). */
    public record ReversePaymentRequest(String reason) {
    }

    /**
     * Payment row with DERIVED amounts. allocatedAmount is the sum of the
     * payment's ACTIVE allocations; availableCredit is the verified amount
     * minus that sum and is ZERO for every non-VERIFIED status (pending,
     * rejected and reversed payments contribute neither settled amounts nor
     * credit - PRD FR-07/FR-08).
     */
    public record PaymentDto(Long id, String paymentNumber, Long customerId,
            String customerCode, String customerName, BigDecimal amount, String currencyCode,
            String method, String referenceNumber, Instant receivedAt, Instant recordedAt,
            String status, Long recordedBy, String recordedByName, String notes,
            BigDecimal allocatedAmount, BigDecimal availableCredit, Instant reversedAt,
            Long reversedBy, String reversalReason, Instant createdAt, Instant updatedAt) {
    }

    /** One append-only verification decision (verifier, decision, reason, time). */
    public record VerificationDto(Long id, String decision, String reason, Long verifierId,
            String verifierName, Instant createdAt) {
    }

    /** One allocation row: invoice, amount, ACTIVE/REVERSED status, history. */
    public record AllocationDto(Long id, Long invoiceId, String invoiceNumber,
            BigDecimal amount, String status, Instant createdAt, Instant reversedAt,
            String reversalReason) {
    }

    /** Full payment: row + verification history + allocations. */
    public record PaymentDetailDto(PaymentDto payment, List<VerificationDto> verifications,
            List<AllocationDto> allocations) {
    }

    /** One bounded page; totals computed server-side, never trusted. */
    public record PaymentPage(List<PaymentDto> items, int page, int size,
            long totalElements, int totalPages) {
    }

    /**
     * Creation outcome plus the replay flag: a fresh recording returns
     * replayed=false (HTTP 201); an idempotent replay returns replayed=true
     * (HTTP 200) with the ORIGINAL entity.
     */
    public record PaymentCreateResult(PaymentDetailDto payment, boolean replayed) {
    }

    /**
     * Allocation outcome plus the replay flag (an idempotent replay returns
     * the original payment detail without allocating again).
     */
    public record AllocationResult(PaymentDetailDto payment, boolean replayed) {
    }
}
