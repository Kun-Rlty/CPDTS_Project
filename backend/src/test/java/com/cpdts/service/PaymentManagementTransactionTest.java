package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cpdts.security.CpdtsAuthenticationToken;
import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.support.CpdtsIntegrationTest;
import com.cpdts.web.InvoiceManagementController.CreateInvoiceRequest;
import com.cpdts.web.InvoiceManagementController.InvoiceItemRequest;
import com.cpdts.web.PaymentManagementController.AllocatePaymentRequest;
import com.cpdts.web.PaymentManagementController.AllocationRequest;
import com.cpdts.web.PaymentManagementController.AllocationResult;
import com.cpdts.web.PaymentManagementController.PaymentDetailDto;
import com.cpdts.web.PaymentManagementController.RecordPaymentRequest;
import com.cpdts.web.PaymentManagementController.ReversePaymentRequest;
import com.cpdts.web.PaymentManagementController.VerifyPaymentRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional atomicity of payment mutations (PRD FR-05..FR-09; AGENTS.md
 * rules 9/10): every audit event is written in the SAME transaction as the
 * change it describes, so a failure while writing the audit row must roll
 * back EVERY part of the mutation:
 *
 * <ul>
 *   <li>record: the payment insert, the PAYMENT sequence increment (no
 *       payment number is ever burned) AND the idempotency key row (the key
 *       is never consumed by a failed attempt);</li>
 *   <li>verify: the verification history row AND the status update - the
 *       payment stays PENDING_VERIFICATION;</li>
 *   <li>allocate: the allocation rows AND the idempotency key - the SAME key
 *       still works afterwards (retries are never poisoned);</li>
 *   <li>reverse: the payment status change AND the allocation reversals -
 *       the payment stays VERIFIED with its ACTIVE allocation intact.</li>
 * </ul>
 *
 * <p>The fault is injected by replacing the JSON mapper bean (used only for
 * audit JSON serialization) with a mock that throws. The mock must be of
 * type {@link JsonMapper} because that is the concrete type of Boot's
 * mapper bean. An UNSTUBBED mock returns null from
 * {@code writeValueAsString}, which the audit insert accepts (nullable
 * jsonb columns) - that is what lets setup mutations succeed before the
 * stubbing flips the mapper into a throwing one. The idempotency hash uses
 * MessageDigest (not the mapper), so the injected fault hits ONLY the
 * transactional audit path.
 */
class PaymentManagementTransactionTest extends CpdtsIntegrationTest {

    @MockitoBean
    private JsonMapper objectMapper;

    @Autowired
    private PaymentManagementService paymentManagementService;

    @Autowired
    private InvoiceManagementService invoiceManagementService;

    private final String officerEmail = "officer.jmx.paytx@example.test";
    private final String verifierEmail = "verifier.jmx.paytx@example.test";
    private final String adminEmail = "coadmin.jmx.paytx@example.test";

    private long officerId;
    private long verifierId;
    private long adminId;

    @BeforeEach
    void arrange() {
        clearTestCustomers();
        officerId = ensureActor(officerEmail, "FINANCE_OFFICER");
        verifierId = ensureActor(verifierEmail, "PAYMENT_VERIFIER");
        adminId = ensureActor(adminEmail, "COMPANY_ADMIN");
        // Default actor for setup calls (worker-style calls switch explicitly):
        authenticate(officerId, officerEmail, "Tx Officer");
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        deleteAllTestUsers();
    }

    @Test
    void auditFailureRollsBackThePaymentInsertSequenceAndIdempotencyKey() {
        long sequenceBefore = nextPaymentSequence();
        long customer = newCustomer("Tx Rollback Co");
        long paymentCountBefore = jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE company_id = ?", Long.class,
                companyId("JMX"));
        long auditBefore = countAudit("PAYMENT_RECORDED");
        String key = "tx-create-" + UUID.randomUUID();

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("simulated audit failure"));

        assertThatThrownBy(() -> paymentManagementService.recordPayment(companyId("JMX"),
                new RecordPaymentRequest(customer, new BigDecimal("1500.00"), "CASH",
                        "2026-05-10", "REF-TX", "rollback victim"), key))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit failure");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE company_id = ?", Long.class,
                companyId("JMX")))
                .as("the payment insert must roll back with the failed audit write")
                .isEqualTo(paymentCountBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE company_id = ? AND customer_id = ?",
                Long.class, companyId("JMX"), customer)).isZero();
        assertThat(nextPaymentSequence())
                .as("the sequence increment must roll back too - no number is burned")
                .isEqualTo(sequenceBefore);
        assertThat(keyRows(key))
                .as("the idempotency key must not be consumed by a failed attempt")
                .isZero();
        assertThat(countAudit("PAYMENT_RECORDED"))
                .as("no partial audit row may exist for the failed recording")
                .isEqualTo(auditBefore);
    }

    @Test
    void auditFailureRollsBackTheVerificationDecisionAndStatusChange() {
        long customer = newCustomer("Tx Verify Co");
        // Setup mutations run with the UNSTUBBED mock (null JSON accepted):
        PaymentDetailDto pending = paymentManagementService.recordPayment(companyId("JMX"),
                new RecordPaymentRequest(customer, new BigDecimal("900.00"), "BANK_TRANSFER",
                        "2026-05-10", null, "verify rollback"), "tx-verify-"
                        + UUID.randomUUID()).payment();
        long paymentId = pending.payment().id();
        long verificationCountBefore = jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, paymentId);
        long auditBefore = countAudit("PAYMENT_VERIFIED", paymentId);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId)).isEqualTo("PENDING_VERIFICATION");

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("simulated audit failure"));

        assertThatThrownBy(() -> actingAs(verifierId, verifierEmail, "Tx Verifier",
                        () -> paymentManagementService.verifyPayment(companyId("JMX"), paymentId,
                                new VerifyPaymentRequest("should never persist"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit failure");

        // The status UPDATE and the history INSERT ran BEFORE the audit
        // insert inside the same transaction - both must revert:
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId))
                .as("the verification must roll back - the payment stays pending")
                .isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, paymentId))
                .isEqualTo(verificationCountBefore);
        assertThat(countAudit("PAYMENT_VERIFIED", paymentId))
                .as("no partial audit row may exist for the failed decision")
                .isEqualTo(auditBefore);

        // The same decision succeeds once the audit write works again:
        org.mockito.Mockito.doReturn(null).when(objectMapper).writeValueAsString(any());
        PaymentDetailDto verified = actingAs(verifierId, verifierEmail, "Tx Verifier",
                () -> paymentManagementService.verifyPayment(companyId("JMX"), paymentId,
                        new VerifyPaymentRequest("retry works")));
        assertThat(verified.payment().status()).isEqualTo("VERIFIED");
        assertThat(verified.verifications()).hasSize(1);
    }

    @Test
    void auditFailureRollsBackTheAllocationAndItsIdempotencyKey() {
        long customer = newCustomer("Tx Allocate Co");
        long invoiceId = newIssuedInvoice(customer, "Tx allocation rollback");
        // Officer records, verifier verifies (different actors, like production):
        long paymentId = actingAs(officerId, officerEmail, "Tx Officer",
                () -> paymentManagementService.recordPayment(companyId("JMX"),
                        new RecordPaymentRequest(customer, new BigDecimal("200.00"), "CASH",
                                "2026-05-10", null, null), "tx-rec-" + UUID.randomUUID())
                        .payment().payment().id());
        actingAs(verifierId, verifierEmail, "Tx Verifier",
                () -> paymentManagementService.verifyPayment(companyId("JMX"), paymentId,
                        new VerifyPaymentRequest(null)));
        long allocationCountBefore = jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ?",
                Long.class, paymentId);
        long auditBefore = countAudit("PAYMENT_ALLOCATED", paymentId);
        String key = "tx-alloc-" + UUID.randomUUID();

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("simulated audit failure"));

        assertThatThrownBy(() -> actingAs(officerId, officerEmail, "Tx Officer",
                        () -> paymentManagementService.allocatePayment(companyId("JMX"), paymentId,
                                new AllocatePaymentRequest(List.of(
                                        new AllocationRequest(invoiceId,
                                                new BigDecimal("60.00")))),
                                key)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit failure");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ?",
                Long.class, paymentId))
                .as("the allocation insert must roll back with the failed audit write")
                .isEqualTo(allocationCountBefore);
        assertThat(keyRows(key))
                .as("the idempotency key must not be consumed by a failed attempt")
                .isZero();
        assertThat(countAudit("PAYMENT_ALLOCATED", paymentId)).isEqualTo(auditBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations "
                        + "WHERE payment_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, paymentId)).isEqualByComparingTo(BigDecimal.ZERO);

        // The SAME key still works after the rollback (retry is never poisoned):
        org.mockito.Mockito.doReturn(null).when(objectMapper).writeValueAsString(any());
        AllocationResult allocated = actingAs(officerId, officerEmail, "Tx Officer",
                () -> paymentManagementService.allocatePayment(companyId("JMX"), paymentId,
                        new AllocatePaymentRequest(List.of(
                                new AllocationRequest(invoiceId, new BigDecimal("60.00")))),
                        key));
        assertThat(allocated.payment().payment().allocatedAmount())
                .isEqualByComparingTo(new BigDecimal("60.00"));
        assertThat(keyRows(key)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackTheReversalAndTheAllocationReversals() {
        long customer = newCustomer("Tx Reverse Co");
        long invoiceId = newIssuedInvoice(customer, "Tx reversal rollback");
        long paymentId = actingAs(officerId, officerEmail, "Tx Officer",
                () -> paymentManagementService.recordPayment(companyId("JMX"),
                        new RecordPaymentRequest(customer, new BigDecimal("300.00"), "CASH",
                                "2026-05-10", null, null),
                        "tx-rev-rec-" + UUID.randomUUID()).payment().payment().id());
        actingAs(verifierId, verifierEmail, "Tx Verifier",
                () -> paymentManagementService.verifyPayment(companyId("JMX"), paymentId,
                        new VerifyPaymentRequest(null)));
        actingAs(officerId, officerEmail, "Tx Officer",
                () -> paymentManagementService.allocatePayment(companyId("JMX"), paymentId,
                        new AllocatePaymentRequest(List.of(
                                new AllocationRequest(invoiceId, new BigDecimal("100.00")))),
                        "tx-rev-alloc-" + UUID.randomUUID()));
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId)).isEqualTo("VERIFIED");
        long activeAllocationsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ? "
                        + "AND status = 'ACTIVE'", Long.class, paymentId);
        long reversedAuditBefore = countAudit("PAYMENT_REVERSED", paymentId);

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("simulated audit failure"));

        // COMPANY_ADMIN holds PAYMENT_REVERSE (V8) - but the recorder rule
        // does not apply to reversal, so adminId is a valid actor here:
        assertThatThrownBy(() -> actingAs(adminId, adminEmail, "Tx Admin",
                        () -> paymentManagementService.reversePayment(companyId("JMX"),
                                paymentId, new ReversePaymentRequest("should never persist"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit failure");

        // The allocation reversal UPDATE and the payment status UPDATE ran
        // BEFORE the audit insert - both must revert atomically:
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId))
                .as("the reversal must roll back - the payment stays VERIFIED")
                .isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ? "
                        + "AND status = 'ACTIVE'", Long.class, paymentId))
                .as("the allocation must stay ACTIVE after the rollback")
                .isEqualTo(activeAllocationsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT reversal_reason FROM payments WHERE id = ?", String.class, paymentId))
                .isNull();
        assertThat(countAudit("PAYMENT_REVERSED", paymentId))
                .isEqualTo(reversedAuditBefore);

        // And the reversal itself works once the audit write works again:
        org.mockito.Mockito.doReturn(null).when(objectMapper).writeValueAsString(any());
        PaymentDetailDto reversed = actingAs(adminId, adminEmail, "Tx Admin",
                () -> paymentManagementService.reversePayment(companyId("JMX"), paymentId,
                        new ReversePaymentRequest("retry works")));
        assertThat(reversed.payment().status()).isEqualTo("REVERSED");
        assertThat(reversed.allocations()).hasSize(1)
                .allMatch(allocation -> "REVERSED".equals(allocation.status()));
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations "
                        + "WHERE payment_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, paymentId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------
    // Fixtures/helpers
    // ------------------------------------------------------------------

    /** One authenticated call as the given actor (like a separate request). */
    private <T> T actingAs(long userId, String email, String name,
            java.util.function.Supplier<T> action) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        authenticate(userId, email, name);
        try {
            return action.get();
        } finally {
            if (previous != null) {
                SecurityContextHolder.getContext().setAuthentication(previous);
            } else {
                SecurityContextHolder.clearContext();
            }
        }
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
            userId = insertUser(email, "tx actor password 1", "ACTIVE", false);
        } else {
            jdbc.update("UPDATE users SET password_hash = ?, status = 'ACTIVE', "
                            + "is_platform_admin = FALSE, updated_at = now() WHERE id = ?",
                    passwordEncoder.encode("tx actor password 1"), existing);
            userId = existing;
        }
        if (jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Integer.class, userId, companyId("JMX")) == 0) {
            addMembership(userId, "JMX", roleCode, "ACTIVE");
        }
        return userId;
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

    /** One ISSUED invoice for the given customer (real invoice service). */
    private long newIssuedInvoice(long customerId, String description) {
        long invoiceId = invoiceManagementService.createInvoice(companyId("JMX"),
                new CreateInvoiceRequest(customerId, "2026-05-01", "2099-05-31", description,
                        List.of(new InvoiceItemRequest("Work", BigDecimal.ONE,
                                new BigDecimal("500.00"))))).id();
        invoiceManagementService.issueInvoice(companyId("JMX"), invoiceId);
        return invoiceId;
    }

    private long nextPaymentSequence() {
        return jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'PAYMENT' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                Long.class);
    }

    private long keyRows(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?",
                Long.class, key);
    }

    private long countAudit(String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ?", Long.class, action);
    }

    private long countAudit(String action, long entityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, entityId);
    }
}
