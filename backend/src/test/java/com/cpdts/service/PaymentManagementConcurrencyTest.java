package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import com.cpdts.security.CpdtsAuthenticationToken;
import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.support.CpdtsIntegrationTest;
import com.cpdts.web.ConflictException;
import com.cpdts.web.InvoiceManagementController.CreateInvoiceRequest;
import com.cpdts.web.InvoiceManagementController.InvoiceDetailDto;
import com.cpdts.web.InvoiceManagementController.InvoiceItemRequest;
import com.cpdts.web.InvoiceManagementController.VoidInvoiceRequest;
import com.cpdts.web.PaymentManagementController.AllocatePaymentRequest;
import com.cpdts.web.PaymentManagementController.AllocationRequest;
import com.cpdts.web.PaymentManagementController.AllocationResult;
import com.cpdts.web.PaymentManagementController.PaymentCreateResult;
import com.cpdts.web.PaymentManagementController.PaymentDetailDto;
import com.cpdts.web.PaymentManagementController.RecordPaymentRequest;
import com.cpdts.web.PaymentManagementController.RejectPaymentRequest;
import com.cpdts.web.PaymentManagementController.ReversePaymentRequest;
import com.cpdts.web.PaymentManagementController.VerifyPaymentRequest;

/**
 * Concurrency behaviour of payment mutations against the real database
 * (PRD FR-05..FR-09; AGENTS.md rule 10 - concurrency controls for payment
 * verification/allocation and invoice balances):
 *
 * <ul>
 *   <li>same-key CREATIONS elect EXACTLY ONE payment: the idempotency key
 *       row commits in the same transaction as the payment, so losers of the
 *       key race roll back completely (payment + sequence) and replay the
 *       winner's entity;</li>
 *   <li>distinct-key CREATIONS produce DISTINCT, CONTIGUOUS payment numbers
 *       (the PAYMENT sequence row is locked inside each creation
 *       transaction);</li>
 *   <li>parallel verify/reject of the SAME payment elect EXACTLY ONE
 *       decision (payment row locked FOR UPDATE, status re-checked after the
 *       lock);</li>
 *   <li>two payments can NEVER overpay one invoice, and one payment can
 *       NEVER be over-allocated: credit and outstanding are re-checked after
 *       the row locks, so exactly one racing request wins and the losers
 *       roll back cleanly;</li>
 *   <li>reversal-versus-allocation and void-versus-allocation serialize
 *       into a single consistent outcome (documented lock ordering: payment
 *       first, then invoices ASC; the invoice void locks only the invoice).
 *       </li>
 * </ul>
 *
 * <p>Calls go through the real transactional services from parallel threads,
 * each with its own security context (like separate HTTP requests).
 */
class PaymentManagementConcurrencyTest extends CpdtsIntegrationTest {

    private static final int THREADS = 8;
    private static final String OFFICER_EMAIL = "officer.jmx.payrace@example.test";
    private static final String VERIFIER_EMAIL = "verifier.jmx.payrace@example.test";
    private static final String ADMIN_EMAIL = "coadmin.jmx.payrace@example.test";

    @Autowired
    private PaymentManagementService paymentManagementService;

    @Autowired
    private InvoiceManagementService invoiceManagementService;

    private long officerId;
    private long verifierId;
    private long adminId;
    private long companyId;

    @BeforeEach
    void arrange() {
        clearTestCustomers();
        companyId = companyId("JMX");
        officerId = ensureActor(OFFICER_EMAIL, "FINANCE_OFFICER");
        verifierId = ensureActor(VERIFIER_EMAIL, "PAYMENT_VERIFIER");
        adminId = ensureActor(ADMIN_EMAIL, "COMPANY_ADMIN");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        deleteAllTestUsers();
    }

    // ------------------------------------------------------------------
    // Creation: one payment per key, contiguous numbers per distinct key
    // ------------------------------------------------------------------

    @Test
    void concurrentSameKeyCreationsProduceExactlyOnePayment() throws Exception {
        long sequenceBefore = paymentSequence();
        long customer = newCustomer("Race SameKey Co");
        String key = "race-same-" + UUID.randomUUID();
        RecordPaymentRequest request = new RecordPaymentRequest(customer,
                new BigDecimal("100.00"), "CASH", "2026-06-01", "RACE", "same key race");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<PaymentCreateResult>> jobs = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                jobs.add(() -> {
                    authenticate(officerId, OFFICER_EMAIL, "Race Officer");
                    try {
                        return paymentManagementService.recordPayment(companyId, request, key);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            List<PaymentCreateResult> results = new ArrayList<>();
            for (Future<PaymentCreateResult> future : pool.invokeAll(jobs)) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }

            // EVERY retry succeeds (replay or creation) and they all agree
            // on ONE payment - a duplicate commit is physically impossible:
            List<Long> ids = results.stream()
                    .map(result -> result.payment().payment().id()).distinct().toList();
            assertThat(ids).as("all threads must resolve to one payment").hasSize(1);
            String number = jdbc.queryForObject(
                    "SELECT payment_number FROM payments WHERE id = ?", String.class, ids.get(0));
            assertThat(number).isEqualTo("JMX-PAY-%05d".formatted(sequenceBefore));
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payments WHERE company_id = ? AND customer_id = ?",
                    Long.class, companyId, customer))
                    .as("exactly ONE payment row may exist for the identical key").isEqualTo(1);
            assertThat(paymentSequence())
                    .as("losers must roll back their sequence increment - one burn only")
                    .isEqualTo(sequenceBefore + 1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM idempotency_keys WHERE company_id = ? "
                            + "AND scope = 'PAYMENT_CREATE' AND idempotency_key = ?",
                    Long.class, companyId, key)).isEqualTo(1);
            assertThat(results.stream().filter(result -> !result.replayed()).count())
                    .as("exactly one thread performs the real creation").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDistinctKeyCreationsProduceContiguousPaymentNumbers() throws Exception {
        int perThread = 2;
        int total = THREADS * perThread;
        long sequenceBefore = paymentSequence();
        long customer = newCustomer("Race Distinct Co");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<PaymentCreateResult>> jobs = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                int n = i;
                jobs.add(() -> {
                    authenticate(officerId, OFFICER_EMAIL, "Race Officer");
                    try {
                        return paymentManagementService.recordPayment(companyId,
                                new RecordPaymentRequest(customer, new BigDecimal("100.00"),
                                        "CASH", "2026-06-01", "RACE-" + n, null),
                                "race-distinct-" + n + "-" + UUID.randomUUID());
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            List<PaymentCreateResult> created = new ArrayList<>();
            for (Future<PaymentCreateResult> future : pool.invokeAll(jobs)) {
                created.add(future.get(60, TimeUnit.SECONDS));
            }

            List<String> numbers = created.stream()
                    .map(result -> result.payment().payment().paymentNumber())
                    .sorted().toList();
            assertThat(numbers).hasSize(total).doesNotHaveDuplicates()
                    .allMatch(value -> value.matches("JMX-PAY-\\d{5}"));
            List<String> expected = new ArrayList<>();
            for (long value = sequenceBefore; value < sequenceBefore + total; value++) {
                expected.add("JMX-PAY-%05d".formatted(value));
            }
            assertThat(numbers)
                    .as("numbers must be exactly the contiguous allocated range")
                    .isEqualTo(expected);
            assertThat(paymentSequence()).isEqualTo(sequenceBefore + total);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payments WHERE company_id = ? AND customer_id = ?",
                    Long.class, companyId, customer)).isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // One decision: verify vs reject elects exactly one winner
    // ------------------------------------------------------------------

    @Test
    void concurrentVerifyAndRejectElectExactlyOneDecision() throws Exception {
        long customer = newCustomer("Race Decision Co");
        // Deliberately PENDING: the race is the FIRST decision on it.
        long paymentId = recordPendingPayment(customer, "400.00");
        long verificationRowsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, paymentId);
        long verifyAuditBefore = countAudit("PAYMENT_VERIFIED", paymentId);
        long rejectAuditBefore = countAudit("PAYMENT_REJECTED", paymentId);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<PaymentDetailDto>> jobs = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                boolean verify = i % 2 == 0;
                jobs.add(() -> {
                    authenticate(verifierId, VERIFIER_EMAIL, "Race Verifier");
                    try {
                        return verify
                                ? paymentManagementService.verifyPayment(companyId, paymentId,
                                        new VerifyPaymentRequest("race verify"))
                                : paymentManagementService.rejectPayment(companyId, paymentId,
                                        new RejectPaymentRequest("race reject"));
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            int winners = 0;
            int conflicts = 0;
            String winningDecision = null;
            for (Future<PaymentDetailDto> future : pool.invokeAll(jobs)) {
                try {
                    PaymentDetailDto result = future.get(60, TimeUnit.SECONDS);
                    winners++;
                    winningDecision = result.payment().status();
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof ConflictException) {
                        conflicts++;
                    } else {
                        throw ex;
                    }
                }
            }

            assertThat(winners)
                    .as("exactly one decision may win the race").isEqualTo(1);
            assertThat(conflicts)
                    .as("every loser must be told the payment is no longer PENDING")
                    .isEqualTo(THREADS - 1);
            assertThat(winningDecision).isIn("VERIFIED", "REJECTED");

            // The stored state agrees: one decision, one history row, one audit:
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                    String.class, paymentId)).isEqualTo(winningDecision);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                    Long.class, paymentId)).isEqualTo(verificationRowsBefore + 1);
            assertThat(countAudit("PAYMENT_VERIFIED", paymentId)
                    + countAudit("PAYMENT_REJECTED", paymentId))
                    .isEqualTo(verifyAuditBefore + rejectAuditBefore + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Money safety: no overpayment of an invoice, no over-allocation
    // ------------------------------------------------------------------

    @Test
    void twoConcurrentPaymentsCannotOverpayOneInvoice() throws Exception {
        long customer = newCustomer("Race Overpay Co");
        long invoiceId = newIssuedInvoice(customer, "Race overpay target",
                new BigDecimal("500.00"));
        long first = recordVerifiedPayment(customer, "400.00");
        long second = recordVerifiedPayment(customer, "400.00");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<AllocationResult>> jobs = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                long paymentId = i % 2 == 0 ? first : second;
                String key = "race-overpay-" + i + "-" + UUID.randomUUID();
                jobs.add(() -> {
                    authenticate(officerId, OFFICER_EMAIL, "Race Officer");
                    try {
                        return paymentManagementService.allocatePayment(companyId, paymentId,
                                new AllocatePaymentRequest(List.of(
                                        new AllocationRequest(invoiceId,
                                                new BigDecimal("400.00")))),
                                key);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            int winners = 0;
            int loserErrors = 0;
            for (Future<AllocationResult> future : pool.invokeAll(jobs)) {
                try {
                    AllocationResult result = future.get(60, TimeUnit.SECONDS);
                    winners++;
                    assertThat(result.payment().payment().id()).isIn(first, second);
                } catch (ExecutionException ex) {
                    // Both messages are legitimate loser outcomes: the
                    // winning payment has no credit left, the other payment
                    // still has credit but the invoice has no more
                    // outstanding balance.
                    if (ex.getCause() instanceof IllegalArgumentException ex2
                            && ex2.getMessage() != null
                            && (ex2.getMessage().contains("outstanding balance")
                                    || ex2.getMessage().contains("available payment credit"))) {
                        loserErrors++;
                    } else {
                        throw ex;
                    }
                }
            }

            // Exactly one 400.00 allocation wins; every other thread is told
            // the invoice no longer has 400.00 outstanding:
            assertThat(winners).as("exactly one allocation may win").isEqualTo(1);
            assertThat(loserErrors).isEqualTo(THREADS - 1);

            // The invoice can never be overpaid:
            BigDecimal allocated = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations "
                            + "WHERE invoice_id = ? AND status = 'ACTIVE'",
                    BigDecimal.class, invoiceId);
            assertThat(allocated).isEqualByComparingTo(new BigDecimal("400"));
            BigDecimal outstanding = jdbc.queryForObject(
                    "SELECT i.total_amount - COALESCE(SUM(a.amount), 0) "
                            + "FROM invoices i LEFT JOIN payment_allocations a "
                            + "ON a.invoice_id = i.id AND a.status = 'ACTIVE' "
                            + "WHERE i.id = ? GROUP BY i.id", BigDecimal.class, invoiceId);
            assertThat(outstanding).isEqualByComparingTo(new BigDecimal("100"));

            // The losing payment kept its FULL credit (its transaction rolled
            // back completely - including its idempotency key row):
            long winner = jdbc.queryForObject(
                    "SELECT payment_id FROM payment_allocations WHERE invoice_id = ? "
                            + "AND status = 'ACTIVE'", Long.class, invoiceId);
            long loser = winner == first ? second : first;
            assertThat(allocatedFor(winner))
                    .as("the winning payment carries exactly the winning allocation")
                    .isEqualByComparingTo(new BigDecimal("400"));
            assertThat(allocatedFor(loser))
                    .as("the losing payment must keep its whole credit")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onePaymentCannotBeOverAllocatedByConcurrentRequests() throws Exception {
        long customer = newCustomer("Race Credit Co");
        long invoiceA = newIssuedInvoice(customer, "Race credit target A",
                new BigDecimal("100.00"));
        long invoiceB = newIssuedInvoice(customer, "Race credit target B",
                new BigDecimal("100.00"));
        long paymentId = recordVerifiedPayment(customer, "100.00");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<AllocationResult>> jobs = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                long invoiceId = i % 2 == 0 ? invoiceA : invoiceB;
                String key = "race-credit-" + i + "-" + UUID.randomUUID();
                jobs.add(() -> {
                    authenticate(officerId, OFFICER_EMAIL, "Race Officer");
                    try {
                        return paymentManagementService.allocatePayment(companyId, paymentId,
                                new AllocatePaymentRequest(List.of(
                                        new AllocationRequest(invoiceId,
                                                new BigDecimal("60.00")))),
                                key);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            int winners = 0;
            int creditErrors = 0;
            for (Future<AllocationResult> future : pool.invokeAll(jobs)) {
                try {
                    future.get(60, TimeUnit.SECONDS);
                    winners++;
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof IllegalArgumentException ex2
                            && ex2.getMessage() != null
                            && ex2.getMessage().contains("available payment credit")) {
                        creditErrors++;
                    } else {
                        throw ex;
                    }
                }
            }

            // 60.00 of 100.00 credit: the first allocation wins, everyone
            // else is told the remaining credit is only 40.00:
            assertThat(winners).as("exactly one allocation may win").isEqualTo(1);
            assertThat(creditErrors).isEqualTo(THREADS - 1);

            assertThat(allocatedFor(paymentId))
                    .as("the payment must never be allocated beyond its amount")
                    .isEqualByComparingTo(new BigDecimal("60"));
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payment_allocations WHERE payment_id = ? "
                            + "AND status = 'ACTIVE'", Long.class, paymentId)).isEqualTo(1);
            String detailStatus = jdbc.queryForObject(
                    "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
            assertThat(detailStatus).isEqualTo("VERIFIED");
            // Credit derived server-side: 100.00 - 60.00 = 40.00:
            assertThat(new BigDecimal("100.00").subtract(allocatedFor(paymentId)))
                    .isEqualByComparingTo(new BigDecimal("40.00"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Lock ordering: reversal vs allocation, void vs allocation
    // ------------------------------------------------------------------

    @Test
    void reversalAndAllocationRaceEndInAConsistentState() throws Exception {
        long customer = newCustomer("Race Reverse Co");
        long invoiceId = newIssuedInvoice(customer, "Race reverse target",
                new BigDecimal("300.00"));
        long paymentId = recordVerifiedPayment(customer, "300.00");
        long reverseAuditBefore = countAudit("PAYMENT_REVERSED", paymentId);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Object>> jobs = new ArrayList<>();
            // Four reversals (admin) and four allocations (officer), all
            // against the SAME payment - the payment row lock serializes them:
            for (int i = 0; i < THREADS / 2; i++) {
                jobs.add(() -> {
                    authenticate(adminId, ADMIN_EMAIL, "Race Admin");
                    try {
                        return paymentManagementService.reversePayment(companyId, paymentId,
                                new ReversePaymentRequest("race reversal"));
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }
            for (int i = 0; i < THREADS / 2; i++) {
                String key = "race-revalloc-" + i + "-" + UUID.randomUUID();
                jobs.add(() -> {
                    authenticate(officerId, OFFICER_EMAIL, "Race Officer");
                    try {
                        return paymentManagementService.allocatePayment(companyId, paymentId,
                                new AllocatePaymentRequest(List.of(
                                        new AllocationRequest(invoiceId,
                                                new BigDecimal("300.00")))),
                                key);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            int reversalWinners = 0;
            int allocationWinners = 0;
            for (Future<?> future : pool.invokeAll(jobs)) {
                try {
                    Object result = future.get(60, TimeUnit.SECONDS);
                    if (result instanceof PaymentDetailDto) {
                        reversalWinners++;
                    } else if (result instanceof AllocationResult) {
                        allocationWinners++;
                    }
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof ConflictException
                            || ex.getCause() instanceof IllegalArgumentException) {
                        // losers: payment already REVERSED (409) or credit
                        // already consumed by a racing allocation (400)
                        continue;
                    }
                    throw ex;
                }
            }

            // The reversal always succeeds exactly once; the allocation may
            // win BEFORE the reversal (and is then reversed with it) or lose:
            assertThat(reversalWinners).isEqualTo(1);
            assertThat(allocationWinners).isIn(0, 1);

            // ONE consistent final state:
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                    String.class, paymentId)).isEqualTo("REVERSED");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payment_allocations WHERE payment_id = ? "
                            + "AND status = 'ACTIVE'", Long.class, paymentId))
                    .as("no ACTIVE allocation may survive the reversal").isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations "
                            + "WHERE invoice_id = ? AND status = 'ACTIVE'",
                    BigDecimal.class, invoiceId))
                    .as("the invoice balance must be fully restored")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(countAudit("PAYMENT_REVERSED", paymentId))
                    .isEqualTo(reverseAuditBefore + 1);

            // All history is preserved (no deletes anywhere):
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                    Long.class, paymentId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payment_allocations WHERE payment_id = ?",
                    Long.class, paymentId)).isEqualTo(allocationWinners);
            if (allocationWinners == 1) {
                java.util.Map<String, Object> allocation = jdbc.queryForMap(
                        "SELECT status, reversed_at, reversed_by, reversal_reason "
                                + "FROM payment_allocations WHERE payment_id = ?", paymentId);
                assertThat(allocation.get("status")).isEqualTo("REVERSED");
                assertThat(allocation.get("reversed_at")).isNotNull();
                assertThat(((Number) allocation.get("reversed_by")).longValue())
                        .isEqualTo(adminId);
                assertThat(allocation.get("reversal_reason")).isEqualTo("race reversal");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void voidAndAllocationRaceEndInAConsistentState() throws Exception {
        long customer = newCustomer("Race Void Co");
        long invoiceId = newIssuedInvoice(customer, "Race void target",
                new BigDecimal("300.00"));
        long paymentId = recordVerifiedPayment(customer, "300.00");
        long voidAuditBefore = countAudit("INVOICE_VOIDED", invoiceId);
        long allocationAuditBefore = countAudit("PAYMENT_ALLOCATED", paymentId);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Object>> jobs = new ArrayList<>();
            for (int i = 0; i < THREADS / 2; i++) {
                jobs.add(() -> {
                    authenticate(officerId, OFFICER_EMAIL, "Race Officer");
                    try {
                        return invoiceManagementService.voidInvoice(companyId, invoiceId,
                                new VoidInvoiceRequest("race void"));
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }
            for (int i = 0; i < THREADS / 2; i++) {
                String key = "race-voidalloc-" + i + "-" + UUID.randomUUID();
                jobs.add(() -> {
                    authenticate(officerId, OFFICER_EMAIL, "Race Officer");
                    try {
                        return paymentManagementService.allocatePayment(companyId, paymentId,
                                new AllocatePaymentRequest(List.of(
                                        new AllocationRequest(invoiceId,
                                                new BigDecimal("300.00")))),
                                key);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            int voidWinners = 0;
            int allocationWinners = 0;
            for (Future<?> future : pool.invokeAll(jobs)) {
                try {
                    Object result = future.get(60, TimeUnit.SECONDS);
                    if (result instanceof InvoiceDetailDto) {
                        voidWinners++;
                    } else if (result instanceof AllocationResult) {
                        allocationWinners++;
                    }
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof ConflictException
                            || ex.getCause() instanceof IllegalArgumentException) {
                        // losers: invoice already has ACTIVE allocations (409),
                        // is already VOIDED (409), or the credit is consumed
                        continue;
                    }
                    throw ex;
                }
            }

            // The invoice row lock serializes void against allocation: the
            // two sides cannot both win.
            assertThat(voidWinners + allocationWinners)
                    .as("exactly one side of the race may win").isEqualTo(1);

            if (voidWinners == 1) {
                assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?",
                        String.class, invoiceId)).isEqualTo("VOIDED");
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_allocations WHERE invoice_id = ?",
                        Long.class, invoiceId))
                        .as("a voided invoice can never keep an allocation").isZero();
                assertThat(countAudit("INVOICE_VOIDED", invoiceId))
                        .isEqualTo(voidAuditBefore + 1);
                assertThat(countAudit("PAYMENT_ALLOCATED", paymentId))
                        .isEqualTo(allocationAuditBefore);
                assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                        String.class, paymentId)).isEqualTo("VERIFIED");
            } else {
                assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?",
                        String.class, invoiceId)).isEqualTo("ISSUED");
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM payment_allocations WHERE invoice_id = ? "
                                + "AND status = 'ACTIVE'", Long.class, invoiceId)).isEqualTo(1);
                assertThat(allocatedFor(paymentId))
                        .isEqualByComparingTo(new BigDecimal("300"));
                assertThat(countAudit("PAYMENT_ALLOCATED", paymentId))
                        .isEqualTo(allocationAuditBefore + 1);
                assertThat(countAudit("INVOICE_VOIDED", invoiceId))
                        .as("the losing void must not leave an audit row")
                        .isEqualTo(voidAuditBefore);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures/helpers
    // ------------------------------------------------------------------

    /** Records a PENDING payment as the officer (no decision yet). */
    private long recordPendingPayment(long customerId, String amount) {
        authenticate(officerId, OFFICER_EMAIL, "Race Officer");
        try {
            return paymentManagementService.recordPayment(companyId,
                    new RecordPaymentRequest(customerId, new BigDecimal(amount), "CASH",
                            "2026-06-01", null, null),
                    "race-rec-" + UUID.randomUUID()).payment().payment().id();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** Records a payment (officer) and verifies it (verifier) - two actors. */
    private long recordVerifiedPayment(long customerId, String amount) {
        long paymentId = recordPendingPayment(customerId, amount);
        authenticate(verifierId, VERIFIER_EMAIL, "Race Verifier");
        try {
            paymentManagementService.verifyPayment(companyId, paymentId,
                    new VerifyPaymentRequest(null));
        } finally {
            SecurityContextHolder.clearContext();
        }
        return paymentId;
    }

    /** One ISSUED invoice for the given customer (real invoice service). */
    private long newIssuedInvoice(long customerId, String description, BigDecimal total) {
        authenticate(officerId, OFFICER_EMAIL, "Race Officer");
        try {
            long invoiceId = invoiceManagementService.createInvoice(companyId,
                    new CreateInvoiceRequest(customerId, "2026-06-01", "2099-06-30",
                            description,
                            List.of(new InvoiceItemRequest("Work", BigDecimal.ONE, total))))
                    .id();
            invoiceManagementService.issueInvoice(companyId, invoiceId);
            return invoiceId;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long newCustomer(String name) {
        jdbc.update("""
                INSERT INTO customers (company_id, display_name, status)
                SELECT id, ?, 'ACTIVE' FROM companies WHERE code = 'JMX'
                """, name);
        return jdbc.queryForObject(
                "SELECT id FROM customers WHERE display_name = ? AND company_id = "
                        + "(SELECT id FROM companies WHERE code = 'JMX')",
                Long.class, name);
    }

    private void authenticate(long userId, String email, String name) {
        SecurityContextHolder.getContext().setAuthentication(
                CpdtsAuthenticationToken.authenticated(
                        new CpdtsPrincipal(userId, email, name, false)));
    }

    /** Creates or resets a fixture user with one JMX role (test DB only). */
    private long ensureActor(String email, String roleCode) {
        Long existing = jdbc.query("SELECT id FROM users WHERE email = ?",
                (rs, rowNum) -> rs.getLong(1), email).stream().findFirst().orElse(null);
        long userId;
        if (existing == null) {
            userId = insertUser(email, "race actor password 1", "ACTIVE", false);
        } else {
            jdbc.update("UPDATE users SET password_hash = ?, status = 'ACTIVE', "
                            + "is_platform_admin = FALSE, updated_at = now() WHERE id = ?",
                    passwordEncoder.encode("race actor password 1"), existing);
            userId = existing;
        }
        if (jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Integer.class, userId, companyId) == 0) {
            addMembershipById(userId, companyId, roleCode);
        }
        return userId;
    }

    private void addMembershipById(long userId, long companyCodeId, String roleCode) {
        jdbc.update("""
                INSERT INTO company_memberships (company_id, user_id, role_id, status)
                SELECT ?, ?, r.id, 'ACTIVE' FROM roles r WHERE r.code = ?
                """, companyCodeId, userId, roleCode);
    }

    private long paymentSequence() {
        return jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'PAYMENT' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                Long.class);
    }

    private BigDecimal allocatedFor(long paymentId) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations "
                        + "WHERE payment_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, paymentId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private long countAudit(String action, long entityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, entityId);
    }
}
