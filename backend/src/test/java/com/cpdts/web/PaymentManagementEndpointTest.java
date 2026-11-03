package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cpdts.support.CpdtsIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * API-level tests for company-scoped payment management (PRD FR-05..FR-09):
 *
 * <ul>
 *   <li>permissions: RECORD_VIEW for reads, PAYMENT_RECORD for recording and
 *       allocating, PAYMENT_VERIFY for decisions, PAYMENT_REVERSE (V8) for
 *       whole-payment reversal; the recorder can NEVER decide their own
 *       payment, including platform admins;</li>
 *   <li>company isolation: foreign payments are 404 through my path, foreign
 *       companies are 403;</li>
 *   <li>server-side derivation: payment number (PAYMENT sequence), recorder,
 *       company, TZS currency and PENDING_VERIFICATION status are never
 *       client-supplied; forged body fields are ignored;</li>
 *   <li>lifecycle: PENDING -&gt; exactly one decision (VERIFIED/REJECTED with
 *       a mandatory rejection reason), VERIFIED -&gt; REVERSED (admin only,
 *       mandatory reason), REJECTED/REVERSED terminal, no edits, no deletes;</li>
 *   <li>allocation: explicit only, VERIFIED payments to ISSUED invoices of
 *       the same customer/company, partial and multi-invoice, never beyond
 *       available credit or invoice outstanding, idempotent under a required
 *       Idempotency-Key;</li>
 *   <li>credit semantics: only VERIFIED funds carry available credit -
 *       pending, rejected and reversed payments contribute nothing;</li>
 *   <li>idempotency: same key + same request replays (200), same key +
 *       different request is 409, keys are required and company-scoped;</li>
 *   <li>bounded company-scoped pagination/filters, CSRF, and NO edit or
 *       delete routes anywhere on payment paths.</li>
 * </ul>
 *
 * <p>All mutations run only against {@code cpdts_test} (guarded by the base
 * class). Money assertions read the DATABASE value as the authority and the
 * response body as the contract. Audit and sequence assertions are
 * RUN-RELATIVE (append-only audit rows and advanced counters persist across
 * runs in the scratch test database).
 */
class PaymentManagementEndpointTest extends CpdtsIntegrationTest {

    private static final String ACTOR_PASSWORD = "fixture actor password 1";

    // Fixed fixture emails on purpose: payment mutations are audited and the
    // audit actor can never be deleted (append-only audit_logs FK), so a
    // bounded set of reusable actors keeps the test database bounded.
    private static final String PLATFORM_ACTOR_EMAIL = "platform.actor@example.test";
    private static final String OFFICER_JMX_EMAIL = "officer.jmx.payment@example.test";
    private static final String VERIFIER_JMX_EMAIL = "verifier.jmx.payment@example.test";
    private static final String VIEWER_JMX_EMAIL = "viewer.jmx.payment@example.test";
    private static final String COADMIN_JMX_EMAIL = "coadmin.jmx.payment@example.test";
    private static final String COADMIN_KAM_EMAIL = "coadmin.kam.payment@example.test";

    @BeforeEach
    void startWithNoPayments() {
        // Full financial-state reset: TRUNCATE customers CASCADE empties
        // invoices, invoice_items, payments, allocations and verifications
        // (the only legal cleanup path around the no-delete triggers).
        // idempotency_keys and number_sequences are NOT truncated, so keys
        // are unique per test (UUID) and sequence assertions are relative.
        clearTestCustomers();
    }

    @AfterEach
    void cleanUpTestUsers() {
        deleteAllTestUsers();
    }

    // ------------------------------------------------------------------
    // Record: server derivation, numbering, audit, forged fields ignored
    // ------------------------------------------------------------------

    @Test
    void financeOfficerRecordsPaymentWithServerDerivedFieldsAndAudit() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Payment Alpha Traders", "ACTIVE");
        long sequenceBefore = nextPaymentSequence("JMX");
        long kamSequenceBefore = nextPaymentSequence("KAM");

        // paymentNumber / status / currencyCode / recordedBy / allocatedAmount
        // in the body must be IGNORED (they are absent from the request DTO):
        String body = """
                {"customerId":%d,"amount":1500.00,"method":"cash","receivedAt":"2026-05-10",
                 "referenceNumber":"REF-100","notes":"counter sale",
                 "paymentNumber":"FORGED","status":"VERIFIED","currencyCode":"USD",
                 "recordedBy":999999,"allocatedAmount":500.00}""".formatted(customer);

        MvcResult created = mockMvc.perform(
                        recordPayment(jmx, session, token, body, "rec-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.payment.currencyCode").value("TZS"))
                .andExpect(jsonPath("$.payment.method").value("CASH"))
                .andExpect(jsonPath("$.payment.paymentNumber")
                        .value("JMX-PAY-%05d".formatted(sequenceBefore)))
                .andExpect(jsonPath("$.payment.customerId").value(customer))
                .andExpect(jsonPath("$.payment.recordedBy").value(officer))
                .andExpect(jsonPath("$.payment.recordedByName").isNotEmpty())
                .andExpect(jsonPath("$.payment.receivedAt").isNotEmpty())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(0.00))
                // a PENDING payment must never offer credit:
                .andExpect(jsonPath("$.payment.availableCredit").value(0.00))
                .andReturn();
        String response = created.getResponse().getContentAsString();
        long paymentId = decimal(response, "$.payment.id").longValue();
        assertThat(decimal(response, "$.payment.amount")).isEqualByComparingTo("1500.00");

        // Database is authoritative:
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, method, amount, currency_code, customer_id, payment_number, "
                        + "recorded_by, reference_number, notes, received_at FROM payments "
                        + "WHERE id = ? AND company_id = ?", paymentId, jmx);
        assertThat(row.get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(row.get("method")).isEqualTo("CASH");
        assertThat(row.get("currency_code")).isEqualTo("TZS");
        assertThat(row.get("payment_number"))
                .isEqualTo("JMX-PAY-%05d".formatted(sequenceBefore));
        assertThat(row.get("reference_number")).isEqualTo("REF-100");
        assertThat(row.get("notes")).isEqualTo("counter sale");
        assertThat(new BigDecimal(row.get("amount").toString()))
                .isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(((Number) row.get("customer_id")).longValue()).isEqualTo(customer);
        assertThat(((Number) row.get("recorded_by")).longValue())
                .as("the recorder is derived from the session actor, never the body")
                .isEqualTo(officer);
        // Date-only input is stored at the start of the Tanzania business date.
        assertThat(row.get("received_at"))
                .isEqualTo(Timestamp.from(Instant.parse("2026-05-09T21:00:00Z")));

        // Numbering is company-scoped and advances exactly once (run-relative
        // because counters persist across runs in the scratch test database):
        assertThat(nextPaymentSequence("JMX")).isEqualTo(sequenceBefore + 1);
        assertThat(nextPaymentSequence("KAM")).isEqualTo(kamSequenceBefore)
                .as("the KAM counter must be untouched by a JMX recording");

        // Detail read: empty history, no allocations, derived amounts:
        mockMvc.perform(getPayment(jmx, paymentId, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(paymentId))
                .andExpect(jsonPath("$.payment.paymentNumber")
                        .value("JMX-PAY-%05d".formatted(sequenceBefore)))
                .andExpect(jsonPath("$.verifications.length()").value(0))
                .andExpect(jsonPath("$.allocations.length()").value(0));

        // Atomic audit event with the session actor and a minimized summary:
        java.util.Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, company_id, entity_type, "
                        + "after_data->>'status' AS status, "
                        + "after_data->>'payment_number' AS number, "
                        + "after_data->>'amount' AS amount, "
                        + "after_data->>'method' AS method, "
                        + "after_data::text AS data "
                        + "FROM audit_logs WHERE action = 'PAYMENT_RECORDED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1", paymentId);
        assertThat(audit.get("actor_user_id")).isEqualTo(officer);
        assertThat(audit.get("company_id")).isEqualTo(jmx);
        assertThat(audit.get("entity_type")).isEqualTo("PAYMENT");
        assertThat(audit.get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(audit.get("number")).isEqualTo("JMX-PAY-%05d".formatted(sequenceBefore));
        assertThat(audit.get("amount")).isEqualTo("1500.00");
        assertThat(audit.get("method")).isEqualTo("CASH");
        assertThat((String) audit.get("data"))
                .as("audit data is minimized - never the full free text")
                .doesNotContain("FORGED").doesNotContain("counter sale");
    }

    // ------------------------------------------------------------------
    // Idempotency: required header, replay, key-reuse rejection
    // ------------------------------------------------------------------

    @Test
    void idempotencyKeyIsRequiredReplaysIdenticalRequestsAndRejectsKeyReuse() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Idempotency Co", "ACTIVE");
        long sequenceBefore = nextPaymentSequence("JMX");
        String key = "idem-" + UUID.randomUUID();
        String body = """
                {"customerId":%d,"amount":1500.00,"method":"CASH","receivedAt":"2026-05-10",
                 "referenceNumber":"REF-IDEM","notes":"first"}""".formatted(customer);

        // Missing / malformed keys are refused before anything is written:
        mockMvc.perform(recordPayment(jmx, session, token, body, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Idempotency-Key header is required for payment recording "
                                + "and allocation"));
        mockMvc.perform(recordPayment(jmx, session, token, body, "bad key!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Idempotency-Key must be 1-64 characters of [A-Za-z0-9._-]"));
        mockMvc.perform(recordPayment(jmx, session, token, body, "k".repeat(65)))
                .andExpect(status().isBadRequest());
        assertThat(paymentCount(jmx)).isZero();

        // First accepted call -> 201:
        MvcResult first = mockMvc.perform(recordPayment(jmx, session, token, body, key))
                .andExpect(status().isCreated())
                .andReturn();
        long paymentId = decimal(first.getResponse().getContentAsString(),
                "$.payment.id").longValue();

        // Byte-identical retry -> 200 replay of the SAME payment:
        MvcResult replay = mockMvc.perform(recordPayment(jmx, session, token, body, key))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(decimal(replay.getResponse().getContentAsString(), "$.payment.id").longValue())
                .as("a replay must return the ORIGINAL payment")
                .isEqualTo(paymentId);
        assertThat(paymentCount(jmx)).isEqualTo(1);

        // Semantically identical retry (canonical request hash: 1500.0 == 1500.00,
        // method case, trimmed text) -> 200 replay, still ONE payment:
        String equivalent = """
                {"customerId":%d,"amount":1500.0,"method":"cash","receivedAt":"2026-05-10",
                 "referenceNumber":" REF-IDEM ","notes":"  first "}""".formatted(customer);
        mockMvc.perform(recordPayment(jmx, session, token, equivalent, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(paymentId))
                // a replay returns the STORED entity, not the retry payload:
                .andExpect(jsonPath("$.payment.notes").value("first"));
        assertThat(paymentCount(jmx)).isEqualTo(1);

        // Same key with a DIFFERENT payload -> 409, no second payment:
        String different = body.replace("1500.00", "2000.00");
        mockMvc.perform(recordPayment(jmx, session, token, different, key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
        assertThat(paymentCount(jmx)).isEqualTo(1);

        // Exactly one key row and exactly one sequence burn for this key:
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE company_id = ? "
                        + "AND scope = 'PAYMENT_CREATE' AND idempotency_key = ?",
                Long.class, jmx, key)).isEqualTo(1);
        assertThat(nextPaymentSequence("JMX"))
                .as("replays and refusals must never burn payment numbers")
                .isEqualTo(sequenceBefore + 1);
        assertThat(officer).isPositive();
    }

    // ------------------------------------------------------------------
    // Validation against the V3 schema (reject, never round)
    // ------------------------------------------------------------------

    @Test
    void recordingValidatesEveryInputAgainstTheSchema() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Validation Payer", "ACTIVE");
        long inactive = newCustomer("JMX", "Inactive Payer", "INACTIVE");
        long foreign = newCustomer("KAM", "KAM Payer", "ACTIVE");

        // Customer: missing / unknown / cross-company:
        assertRecordBadRequest(jmx, session, token,
                "{\"amount\":100.00,\"method\":\"CASH\",\"receivedAt\":\"2026-05-10\"}",
                "customer id is required");
        mockMvc.perform(recordPayment(jmx, session, token,
                        "{\"customerId\":999999999,\"amount\":100.00,\"method\":\"CASH\","
                                + "\"receivedAt\":\"2026-05-10\"}", uniqueKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(recordPayment(jmx, session, token,
                        "{\"customerId\":" + foreign + ",\"amount\":100.00,\"method\":\"CASH\","
                                + "\"receivedAt\":\"2026-05-10\"}", uniqueKey()))
                .andExpect(status().isNotFound());

        // Amount: missing / zero / negative / excess precision / out of bounds.
        // Extra precision is REJECTED, never silently rounded (NUMERIC(14,2)).
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"method\":\"CASH\",\"receivedAt\":\"2026-05-10\"}",
                "amount is required");
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"amount\":0,\"method\":\"CASH\",\"receivedAt\":\"2026-05-10\"}",
                "amount must be positive");
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"amount\":-5.00,\"method\":\"CASH\",\"receivedAt\":\"2026-05-10\"}",
                "amount must be positive");
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"amount\":10.005,\"method\":\"CASH\","
                        + "\"receivedAt\":\"2026-05-10\"}",
                "amount must have at most 2 decimal places");
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"amount\":1000000000000.00,\"method\":\"CASH\","
                        + "\"receivedAt\":\"2026-05-10\"}",
                "amount is out of bounds");

        // Method: missing / unsupported:
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"amount\":100.00,\"receivedAt\":\"2026-05-10\"}",
                "method is required");
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"amount\":100.00,\"method\":\"CHEQUE\","
                        + "\"receivedAt\":\"2026-05-10\"}",
                "method must be CASH, BANK_TRANSFER or MOBILE_MONEY");

        // Received date: missing / malformed / out of the engineering guard:
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer + ",\"amount\":100.00,\"method\":\"CASH\"}",
                "received date is required");
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"amount\":100.00,\"method\":\"CASH\",\"receivedAt\":\"05/10/2026\"}",
                "received date must use yyyy-MM-dd or ISO-8601 timestamp format");
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer
                        + ",\"amount\":100.00,\"method\":\"CASH\",\"receivedAt\":\"1999-01-01\"}",
                "received date must be between 2000 and 2100");

        // Field bounds: reference 100, notes 1000 (101/1001 are refused):
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer + ",\"amount\":100.00,\"method\":\"CASH\","
                        + "\"receivedAt\":\"2026-05-10\",\"referenceNumber\":\""
                        + "r".repeat(101) + "\"}",
                "reference number must be at most 100 characters");
        assertRecordBadRequest(jmx, session, token,
                "{\"customerId\":" + customer + ",\"amount\":100.00,\"method\":\"CASH\","
                        + "\"receivedAt\":\"2026-05-10\",\"notes\":\"" + "n".repeat(1001)
                        + "\"}",
                "notes must be at most 1000 characters");

        // A failed attempt writes NO idempotency key row, so the client can
        // retry the corrected payload under the SAME key:
        String failedKey = uniqueKey();
        mockMvc.perform(recordPayment(jmx, session, token,
                        "{\"customerId\":" + customer + ",\"amount\":0,\"method\":\"CASH\","
                                + "\"receivedAt\":\"2026-05-10\"}", failedKey))
                .andExpect(status().isBadRequest());
        assertThat(keyRows(jmx, failedKey)).isZero();

        // Documented decision: payment recording does NOT require an ACTIVE
        // customer (collecting from a since-deactivated customer stays legal):
        mockMvc.perform(recordPayment(jmx, session, token,
                        "{\"customerId\":" + inactive + ",\"amount\":250.00,"
                                + "\"method\":\"MOBILE_MONEY\",\"receivedAt\":\"2026-05-11\"}",
                        uniqueKey()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.status").value("PENDING_VERIFICATION"));

        // A full ISO-8601 timestamp is accepted as-is (stored verbatim):
        MvcResult timestamped = mockMvc.perform(recordPayment(jmx, session, token,
                        "{\"customerId\":" + customer + ",\"amount\":75.00,"
                                + "\"method\":\"BANK_TRANSFER\","
                                + "\"receivedAt\":\"2026-05-12T10:30:00Z\"}", uniqueKey()))
                .andExpect(status().isCreated())
                .andReturn();
        long timestampedId = decimal(timestamped.getResponse().getContentAsString(),
                "$.payment.id").longValue();
        assertThat(jdbc.queryForObject(
                "SELECT received_at FROM payments WHERE id = ?", Timestamp.class, timestampedId))
                .isEqualTo(Timestamp.from(Instant.parse("2026-05-12T10:30:00Z")));

        // List parameter validation (bounded pagination and filters):
        mockMvc.perform(listPayments(jmx, session).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page must be >= 0"));
        mockMvc.perform(listPayments(jmx, session).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size must be between 1 and 100"));
        mockMvc.perform(listPayments(jmx, session).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(listPayments(jmx, session).param("status", "BANANA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "status must be PENDING_VERIFICATION, VERIFIED, REJECTED or REVERSED"));

        // Unknown company fails closed; non-numeric path is a 400:
        mockMvc.perform(listPayments(999_999_999L, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/api/v1/companies/abc/payments").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        // Only the two ACCEPTED requests created rows:
        assertThat(paymentCount(jmx)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Permissions: reads vs recording vs decisions vs reversal
    // ------------------------------------------------------------------

    @Test
    void viewerCanReadButNeverMutatePayments() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "ReadOnly Payer", "ACTIVE");
        long paymentId = recordPayment(jmx, officerSession, csrfToken(officerSession), customer,
                "100.00", "CASH", "2026-05-10");
        assertThat(officer).isPositive();

        long viewer = roleMember(VIEWER_JMX_EMAIL, "VIEWER", "JMX");
        MockHttpSession viewerSession = login(VIEWER_JMX_EMAIL, ACTOR_PASSWORD);
        String viewerToken = csrfToken(viewerSession);

        // RECORD_VIEW: reads work...
        mockMvc.perform(listPayments(jmx, viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(getPayment(jmx, paymentId, viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(paymentId));

        // ...but every mutation is refused:
        mockMvc.perform(recordPayment(jmx, viewerSession, viewerToken,
                        "{\"customerId\":" + customer + ",\"amount\":10.00,\"method\":\"CASH\","
                                + "\"receivedAt\":\"2026-05-10\"}", uniqueKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(verifyPayment(jmx, paymentId, viewerSession, viewerToken, "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(rejectPayment(jmx, paymentId, viewerSession, viewerToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(allocatePayment(jmx, paymentId, viewerSession, viewerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":1,\"amount\":1.00}]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(reversePayment(jmx, paymentId, viewerSession, viewerToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
        assertThat(viewer).isPositive();

        // Nothing changed:
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, paymentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ?",
                Long.class, paymentId)).isZero();
    }

    @Test
    void permissionSplitGovernsRecordingDecidingAndReversal() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession adminSession = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String officerToken = csrfToken(officerSession);
        String verifierToken = csrfToken(verifierSession);
        String adminToken = csrfToken(adminSession);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Split Payer", "ACTIVE");

        long p1 = recordPayment(jmx, officerSession, officerToken, customer, "100.00", "CASH",
                "2026-05-10");

        // FINANCE_OFFICER: PAYMENT_RECORD yes, PAYMENT_VERIFY / PAYMENT_REVERSE no:
        mockMvc.perform(verifyPayment(jmx, p1, officerSession, officerToken, "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(rejectPayment(jmx, p1, officerSession, officerToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(reversePayment(jmx, p1, officerSession, officerToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());

        // PAYMENT_VERIFIER: PAYMENT_VERIFY yes, PAYMENT_RECORD / PAYMENT_REVERSE no:
        mockMvc.perform(recordPayment(jmx, verifierSession, verifierToken,
                        "{\"customerId\":" + customer + ",\"amount\":10.00,\"method\":\"CASH\","
                                + "\"receivedAt\":\"2026-05-10\"}", uniqueKey()))
                .andExpect(status().isForbidden());
        mockMvc.perform(allocatePayment(jmx, p1, verifierSession, verifierToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":1,\"amount\":1.00}]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(reversePayment(jmx, p1, verifierSession, verifierToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());

        // COMPANY_ADMIN holds PAYMENT_REVERSE (V8): the reverse attempt on a
        // PENDING payment fails with 409 (wrong status), NOT 403 - proving
        // the permission is present and only the lifecycle rule applies:
        mockMvc.perform(reversePayment(jmx, p1, adminSession, adminToken,
                        "{\"reason\":\"not yet verified\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));

        // COMPANY_ADMIN also holds PAYMENT_RECORD:
        long p2 = recordPayment(jmx, adminSession, adminToken, customer, "20.00", "CASH",
                "2026-05-11");
        assertThat(p2).isNotEqualTo(p1);

        // No decision was ever written for p1:
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, p1)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, p1)).isZero();
        assertThat(paymentCount(jmx)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // The recorder can never decide their own payment (service + V3 trigger)
    // ------------------------------------------------------------------

    @Test
    void theRecorderCanNeverDecideTheirOwnPaymentEvenAsPlatformAdmin() throws Exception {
        long admin = roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        MockHttpSession adminSession = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String adminToken = csrfToken(adminSession);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "SelfDecision Payer", "ACTIVE");

        // COMPANY_ADMIN holds BOTH PAYMENT_RECORD and PAYMENT_VERIFY:
        long own = recordPayment(jmx, adminSession, adminToken, customer, "100.00", "CASH",
                "2026-05-10");
        mockMvc.perform(verifyPayment(jmx, own, adminSession, adminToken, "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(rejectPayment(jmx, own, adminSession, adminToken,
                        "{\"reason\":\"my own payment\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, own)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, own)).isZero();

        // Database backstop (V3 fn_check_payment_verifier): even a direct
        // insert that bypasses the service is refused:
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO payment_verifications
                    (company_id, payment_id, verifier_id, decision, reason)
                VALUES (?, ?, ?, 'VERIFIED', NULL)
                """, jmx, own, admin))
                .isInstanceOf(DataIntegrityViolationException.class)
                .as("the V3 trigger must block self-verification at the database level");

        // A platform admin bypasses permission checks - but is NOT exempt
        // from the recorder rule:
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession platformSession = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String platformToken = csrfToken(platformSession);
        long platformOwn = recordPayment(jmx, platformSession, platformToken, customer,
                "50.00", "CASH", "2026-05-10");
        mockMvc.perform(verifyPayment(jmx, platformOwn, platformSession, platformToken, "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, platformOwn)).isEqualTo("PENDING_VERIFICATION");

        // A DIFFERENT holder of PAYMENT_VERIFY may decide the admin's payment:
        long verifier = roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        String verifierToken = csrfToken(verifierSession);
        mockMvc.perform(verifyPayment(jmx, own, verifierSession, verifierToken, "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("VERIFIED"));
        assertThat(verifier).isNotEqualTo(admin);
    }

    // ------------------------------------------------------------------
    // Verify: atomic status + history + audit, single decision only
    // ------------------------------------------------------------------

    @Test
    void verificationIsAtomicWithHistoryAuditAndASingleDecision() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        long verifier = roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        String officerToken = csrfToken(officerSession);
        String verifierToken = csrfToken(verifierSession);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Verify Payer", "ACTIVE");
        long paymentId = recordPayment(jmx, officerSession, officerToken, customer, "1500.00",
                "BANK_TRANSFER", "2026-05-10");
        long verifyAuditBefore = countAudit("PAYMENT_VERIFIED", paymentId);

        MvcResult verified = mockMvc.perform(
                        verifyPayment(jmx, paymentId, verifierSession, verifierToken,
                                "{\"notes\":\"matched bank slip\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("VERIFIED"))
                .andExpect(jsonPath("$.payment.allocatedAmount").value(0.00))
                .andExpect(jsonPath("$.verifications.length()").value(1))
                .andExpect(jsonPath("$.verifications[0].decision").value("VERIFIED"))
                .andExpect(jsonPath("$.verifications[0].reason").value("matched bank slip"))
                .andExpect(jsonPath("$.verifications[0].verifierId").value(verifier))
                .andExpect(jsonPath("$.verifications[0].verifierName").isNotEmpty())
                .andExpect(jsonPath("$.verifications[0].createdAt").isNotEmpty())
                .andReturn();
        String response = verified.getResponse().getContentAsString();
        // Verified + unallocated = full customer credit:
        assertThat(decimal(response, "$.payment.amount")).isEqualByComparingTo("1500.00");
        assertThat(decimal(response, "$.payment.availableCredit")).isEqualByComparingTo("1500.00");

        // Status and history are committed together:
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId)).isEqualTo("VERIFIED");
        java.util.Map<String, Object> verification = jdbc.queryForMap(
                "SELECT decision, reason, verifier_id FROM payment_verifications "
                        + "WHERE payment_id = ? ORDER BY id DESC LIMIT 1", paymentId);
        assertThat(verification.get("decision")).isEqualTo("VERIFIED");
        assertThat(verification.get("reason")).isEqualTo("matched bank slip");
        assertThat(((Number) verification.get("verifier_id")).longValue()).isEqualTo(verifier);

        // Audit is written in the same transaction with the before/after diff:
        java.util.Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, before_data->>'status' AS before_status, "
                        + "after_data->>'status' AS after_status "
                        + "FROM audit_logs WHERE action = 'PAYMENT_VERIFIED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1", paymentId);
        assertThat(audit.get("actor_user_id")).isEqualTo(verifier);
        assertThat(audit.get("before_status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(audit.get("after_status")).isEqualTo("VERIFIED");
        assertThat(countAudit("PAYMENT_VERIFIED", paymentId))
                .isEqualTo(verifyAuditBefore + 1);

        // Only PENDING payments can receive a decision (409, not a second row):
        mockMvc.perform(verifyPayment(jmx, paymentId, verifierSession, verifierToken, "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("only PENDING payments can receive a decision"));
        mockMvc.perform(rejectPayment(jmx, paymentId, verifierSession, verifierToken,
                        "{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, paymentId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Reject: mandatory reason, terminal state, credit stays zero
    // ------------------------------------------------------------------

    @Test
    void rejectionRequiresAMandatoryReasonAndIsTerminal() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession adminSession = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String officerToken = csrfToken(officerSession);
        String verifierToken = csrfToken(verifierSession);
        String adminToken = csrfToken(adminSession);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Reject Payer", "ACTIVE");
        long paymentId = recordPayment(jmx, officerSession, officerToken, customer, "400.00",
                "CASH", "2026-05-10");
        long rejectAuditBefore = countAudit("PAYMENT_REJECTED", paymentId);

        // The reason is mandatory (V3 ck_payment_verifications_reason):
        mockMvc.perform(rejectPayment(jmx, paymentId, verifierSession, verifierToken, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("rejection reason is required"));
        mockMvc.perform(rejectPayment(jmx, paymentId, verifierSession, verifierToken,
                        "{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("rejection reason is required"));
        mockMvc.perform(rejectPayment(jmx, paymentId, verifierSession, verifierToken,
                        "{\"reason\":\"" + "r".repeat(10_001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("rejection reason must be at most 10000 characters"));
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, paymentId)).isZero();

        // A valid rejection commits status + history + audit together:
        mockMvc.perform(rejectPayment(jmx, paymentId, verifierSession, verifierToken,
                        "{\"reason\":\"duplicate entry\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("REJECTED"))
                .andExpect(jsonPath("$.payment.allocatedAmount").value(0.00))
                // a REJECTED payment must never offer credit:
                .andExpect(jsonPath("$.payment.availableCredit").value(0.00))
                .andExpect(jsonPath("$.verifications.length()").value(1))
                .andExpect(jsonPath("$.verifications[0].decision").value("REJECTED"))
                .andExpect(jsonPath("$.verifications[0].reason").value("duplicate entry"));

        java.util.Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, reason, before_data->>'status' AS before_status, "
                        + "after_data->>'status' AS after_status "
                        + "FROM audit_logs WHERE action = 'PAYMENT_REJECTED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1", paymentId);
        assertThat(audit.get("actor_user_id")).isEqualTo(
                jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                        VERIFIER_JMX_EMAIL));
        assertThat(audit.get("reason")).isEqualTo("duplicate entry");
        assertThat(audit.get("before_status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(audit.get("after_status")).isEqualTo("REJECTED");
        assertThat(countAudit("PAYMENT_REJECTED", paymentId))
                .isEqualTo(rejectAuditBefore + 1);

        // REJECTED is terminal: no re-decision, no allocation, no reversal:
        mockMvc.perform(verifyPayment(jmx, paymentId, verifierSession, verifierToken, "{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(rejectPayment(jmx, paymentId, verifierSession, verifierToken,
                        "{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
        // PAYMENT_RECORD (not PAYMENT_VERIFY) is required to allocate:
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken,
                        uniqueKey(), "{\"allocations\":[{\"invoiceId\":1,\"amount\":1.00}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("only VERIFIED payments can be allocated"));
        mockMvc.perform(reversePayment(jmx, paymentId, adminSession, adminToken,
                        "{\"reason\":\"try\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("only VERIFIED payments can be reversed"));

        // History and row are preserved, fully readable:
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE id = ?", Long.class, paymentId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, paymentId)).isEqualTo(1);
        mockMvc.perform(getPayment(jmx, paymentId, officerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("REJECTED"))
                .andExpect(jsonPath("$.payment.reversedAt").doesNotExist())
                .andExpect(jsonPath("$.payment.reversalReason").doesNotExist());
    }

    // ------------------------------------------------------------------
    // Allocation preconditions: verified payment, ISSUED, same customer
    // ------------------------------------------------------------------

    @Test
    void allocationRequiresAVerifiedPaymentIssuedInvoicesAndTheSameCustomer() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        String officerToken = csrfToken(officerSession);
        String verifierToken = csrfToken(verifierSession);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Allocate Payer", "ACTIVE");
        long otherCustomer = newCustomer("JMX", "Someone Else", "ACTIVE");

        long issued = createIssued(jmx, officerSession, officerToken, customer,
                "[{\"description\":\"Work\",\"quantity\":1,\"unitPrice\":100.00}]");
        long draft = createDraft(jmx, officerSession, officerToken, customer,
                "[{\"description\":\"Draft\",\"quantity\":1,\"unitPrice\":50.00}]");
        long voided = createDraft(jmx, officerSession, officerToken, customer,
                "[{\"description\":\"Voided\",\"quantity\":1,\"unitPrice\":50.00}]");
        mockMvc.perform(voidInvoice(jmx, voided, officerSession, officerToken,
                        "{\"reason\":\"never happened\"}"))
                .andExpect(status().isOk());
        long foreignCustomerInvoice = createIssued(jmx, officerSession, officerToken,
                otherCustomer, "[{\"description\":\"Other\",\"quantity\":1,\"unitPrice\":50.00}]");

        // A KAM invoice exists but must be unreachable through the JMX path:
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession platformSession = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String platformToken = csrfToken(platformSession);
        long kamCustomer = newCustomer("KAM", "KAM Allocate Payer", "ACTIVE");
        long kamInvoice = createIssued(companyId("KAM"), platformSession, platformToken,
                kamCustomer, "[{\"description\":\"KAM\",\"quantity\":1,\"unitPrice\":50.00}]");

        long paymentId = recordPayment(jmx, officerSession, officerToken, customer, "500.00",
                "CASH", "2026-05-10");

        // A PENDING payment cannot be allocated at all:
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + issued + ",\"amount\":50.00}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("only VERIFIED payments can be allocated"));

        long verifier = jdbc.queryForObject("SELECT id FROM users WHERE email = ?",
                Long.class, VERIFIER_JMX_EMAIL);
        mockMvc.perform(verifyPayment(jmx, paymentId, verifierSession, verifierToken, "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("VERIFIED"));
        assertThat(verifier).isPositive();

        // Invoice state rules:
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + draft + ",\"amount\":10.00}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("only ISSUED invoices can receive allocations"));
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + voided + ",\"amount\":10.00}]}"))
                .andExpect(status().isConflict());

        // Same customer rule: another customer's invoice is 404 (never a 500
        // from the V3 composite FK), unknown ids are 404, other companies 404:
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + foreignCustomerInvoice
                                + ",\"amount\":10.00}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":999999999,\"amount\":10.00}]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + kamInvoice
                                + ",\"amount\":10.00}]}"))
                .andExpect(status().isNotFound());

        // Request shape: empty, missing header, duplicate line, too many lines:
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("at least one allocation line is required"));
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("at least one allocation line is required"));
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, null,
                        "{\"allocations\":[{\"invoiceId\":" + issued + ",\"amount\":1.00}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Idempotency-Key header is required for payment recording "
                                + "and allocation"));
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + issued + ",\"amount\":10.00},"
                                + "{\"invoiceId\":" + issued + ",\"amount\":10.00}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("each invoice may appear at most once per allocation request"));
        StringBuilder many = new StringBuilder("{\"allocations\":[");
        for (int i = 0; i < 101; i++) {
            many.append(i == 0 ? "" : ",").append("{\"invoiceId\":").append(issued)
                    .append(",\"amount\":1.00}");
        }
        many.append("]}");
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        many.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("at most 100 allocation lines are allowed"));

        // NOTHING was allocated by any refused request - full credit remains:
        String detail = mockMvc.perform(getPayment(jmx, paymentId, officerSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(decimal(detail, "$.payment.allocatedAmount")).isEqualByComparingTo("0.00");
        assertThat(decimal(detail, "$.payment.availableCredit")).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ?",
                Long.class, paymentId)).isZero();

        // Positive control: a valid allocation succeeds:
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + issued + ",\"amount\":50.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(50.00))
                .andExpect(jsonPath("$.payment.availableCredit").value(450.00))
                .andExpect(jsonPath("$.allocations.length()").value(1))
                .andExpect(jsonPath("$.allocations[0].invoiceId").value(issued))
                .andExpect(jsonPath("$.allocations[0].invoiceNumber").isNotEmpty())
                .andExpect(jsonPath("$.allocations[0].status").value("ACTIVE"));
    }

    // ------------------------------------------------------------------
    // Partial / multi-invoice allocation: credit and outstanding bounds
    // ------------------------------------------------------------------

    @Test
    void partialAndMultiInvoiceAllocationsRespectCreditAndOutstanding() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        String officerToken = csrfToken(officerSession);
        String verifierToken = csrfToken(verifierSession);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Partial Payer", "ACTIVE");

        long invoiceA = createIssued(jmx, officerSession, officerToken, customer,
                "[{\"description\":\"A\",\"quantity\":1,\"unitPrice\":100.00}]");
        long invoiceB = createIssued(jmx, officerSession, officerToken, customer,
                "[{\"description\":\"B\",\"quantity\":1,\"unitPrice\":500.00}]");

        long small = recordAndVerify(jmx, officerSession, officerToken, verifierSession,
                verifierToken, customer, "300.00");
        long big = recordAndVerify(jmx, officerSession, officerToken, verifierSession,
                verifierToken, customer, "1000.00");

        // 1) Partial single-invoice allocation:
        mockMvc.perform(allocatePayment(jmx, small, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceA + ",\"amount\":60.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(60.00))
                .andExpect(jsonPath("$.payment.availableCredit").value(240.00));
        assertInvoiceState(jmx, officerSession, invoiceA, "PARTIALLY_PAID", "40.00");

        // 2) One request spanning TWO invoices (partial on each):
        mockMvc.perform(allocatePayment(jmx, small, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceA + ",\"amount\":40.00},"
                                + "{\"invoiceId\":" + invoiceB + ",\"amount\":150.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(250.00))
                .andExpect(jsonPath("$.payment.availableCredit").value(50.00))
                // 60.00 (earlier) + 40.00 + 150.00 = three allocation rows:
                .andExpect(jsonPath("$.allocations.length()").value(3));
        assertInvoiceState(jmx, officerSession, invoiceA, "PAID", "0.00");
        assertInvoiceState(jmx, officerSession, invoiceB, "PARTIALLY_PAID", "350.00");
        assertThat(jdbc.queryForList(
                "SELECT amount FROM payment_allocations WHERE payment_id = ? "
                        + "AND status = 'ACTIVE' ORDER BY id", BigDecimal.class, small))
                .containsExactly(new BigDecimal("60.00"), new BigDecimal("40.00"),
                        new BigDecimal("150.00"));

        // 3) Beyond the payment's remaining credit -> refused (never rounded):
        mockMvc.perform(allocatePayment(jmx, small, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceB + ",\"amount\":50.01}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("allocation exceeds the available payment credit of 50.00"));

        // 4) Exactly the remaining credit is allowed:
        mockMvc.perform(allocatePayment(jmx, small, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceB + ",\"amount\":50.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(300.00))
                .andExpect(jsonPath("$.payment.availableCredit").value(0.00));

        // 5) A fully allocated payment has NO credit left:
        mockMvc.perform(allocatePayment(jmx, small, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceB + ",\"amount\":0.01}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("allocation exceeds the available payment credit of 0.00"));

        // 6) Plenty of credit, but beyond the INVOICE's outstanding balance:
        mockMvc.perform(allocatePayment(jmx, big, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceB + ",\"amount\":300.01}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("allocation exceeds the outstanding balance of invoice "
                                + invoiceB + " (300.00)"));

        // 7) Exactly the outstanding balance settles the invoice:
        mockMvc.perform(allocatePayment(jmx, big, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceB + ",\"amount\":300.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(300.00))
                .andExpect(jsonPath("$.payment.availableCredit").value(700.00));
        assertInvoiceState(jmx, officerSession, invoiceB, "PAID", "0.00");

        // The database agrees with every derived amount:
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations "
                        + "WHERE payment_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, small)).isEqualByComparingTo(new BigDecimal("300"));
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payment_allocations "
                        + "WHERE payment_id = ? AND status = 'ACTIVE'",
                BigDecimal.class, big)).isEqualByComparingTo(new BigDecimal("300"));
    }

    // ------------------------------------------------------------------
    // Allocation idempotency: replay never allocates twice
    // ------------------------------------------------------------------

    @Test
    void allocationIdempotencyReplaysWithoutAllocatingTwice() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        String officerToken = csrfToken(officerSession);
        String verifierToken = csrfToken(verifierSession);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "AllocIdem Payer", "ACTIVE");
        long invoiceId = createIssued(jmx, officerSession, officerToken, customer,
                "[{\"description\":\"Work\",\"quantity\":1,\"unitPrice\":500.00}]");
        long paymentId = recordAndVerify(jmx, officerSession, officerToken, verifierSession,
                verifierToken, customer, "100.00");
        String key = "alloc-idem-" + UUID.randomUUID();
        String body = "{\"allocations\":[{\"invoiceId\":" + invoiceId + ",\"amount\":40.00}]}";

        // First accepted allocation:
        MvcResult first = mockMvc.perform(
                        allocatePayment(jmx, paymentId, officerSession, officerToken, key, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(40.00))
                .andReturn();

        // Identical retry -> same payment detail, NO second allocation:
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, key, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(40.00))
                .andExpect(jsonPath("$.allocations.length()").value(1))
                .andExpect(jsonPath("$.payment.availableCredit").value(60.00));

        // Semantically identical retry (canonical money: 40.0 == 40.00):
        String equivalent = "{\"allocations\":[{\"invoiceId\":" + invoiceId
                + ",\"amount\":40.0}]}";
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, key,
                        equivalent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.allocatedAmount").value(40.00));
        assertThat(activeAllocationCount(paymentId)).isEqualTo(1);

        // Same key with a DIFFERENT payload -> 409, still one allocation:
        String different = "{\"allocations\":[{\"invoiceId\":" + invoiceId
                + ",\"amount\":50.00}]}";
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, key,
                        different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
        assertThat(activeAllocationCount(paymentId)).isEqualTo(1);
        assertThat(decimal(first.getResponse().getContentAsString(),
                "$.payment.allocatedAmount")).isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE company_id = ? "
                        + "AND scope = 'PAYMENT_ALLOCATE' AND idempotency_key = ?",
                Long.class, jmx, key)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Reversal: admin only, reason mandatory, atomic, terminal
    // ------------------------------------------------------------------

    @Test
    void wholePaymentReversalIsAdminOnlyAtomicAndTerminal() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        long verifier = roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        long admin = roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        roleMember(VIEWER_JMX_EMAIL, "VIEWER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession adminSession = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession viewerSession = login(VIEWER_JMX_EMAIL, ACTOR_PASSWORD);
        String officerToken = csrfToken(officerSession);
        String verifierToken = csrfToken(verifierSession);
        String adminToken = csrfToken(adminSession);
        String viewerToken = csrfToken(viewerSession);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Reverse Payer", "ACTIVE");
        long invoiceId = createIssued(jmx, officerSession, officerToken, customer,
                "[{\"description\":\"Work\",\"quantity\":1,\"unitPrice\":300.00}]");
        long paymentId = recordAndVerify(jmx, officerSession, officerToken, verifierSession,
                verifierToken, customer, "300.00");
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceId + ",\"amount\":100.00}]}"))
                .andExpect(status().isOk());
        assertInvoiceState(jmx, officerSession, invoiceId, "PARTIALLY_PAID", "200.00");
        long reverseAuditBefore = countAudit("PAYMENT_REVERSED", paymentId);

        // Only PLATFORM_ADMIN / COMPANY_ADMIN hold PAYMENT_REVERSE (V8):
        mockMvc.perform(reversePayment(jmx, paymentId, officerSession, officerToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(reversePayment(jmx, paymentId, verifierSession, verifierToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(reversePayment(jmx, paymentId, viewerSession, viewerToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());

        // The reason is mandatory (V3 ck_payments_reversed):
        mockMvc.perform(reversePayment(jmx, paymentId, adminSession, adminToken, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reversal reason is required"));
        mockMvc.perform(reversePayment(jmx, paymentId, adminSession, adminToken,
                        "{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId)).isEqualTo("VERIFIED");

        // The reversal flips payment + ALL active allocations in ONE
        // transaction, restoring the invoice balance:
        mockMvc.perform(reversePayment(jmx, paymentId, adminSession, adminToken,
                        "{\"reason\":\"bank recalled the transfer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("REVERSED"))
                .andExpect(jsonPath("$.payment.reversalReason").value("bank recalled the transfer"))
                .andExpect(jsonPath("$.payment.reversedBy").value(admin))
                .andExpect(jsonPath("$.payment.reversedAt").isNotEmpty())
                // reversed allocations no longer consume credit, and a
                // REVERSED payment must never offer credit:
                .andExpect(jsonPath("$.payment.allocatedAmount").value(0.00))
                .andExpect(jsonPath("$.payment.availableCredit").value(0.00))
                // decision history is preserved:
                .andExpect(jsonPath("$.verifications.length()").value(1))
                .andExpect(jsonPath("$.allocations.length()").value(1))
                .andExpect(jsonPath("$.allocations[0].status").value("REVERSED"))
                .andExpect(jsonPath("$.allocations[0].reversalReason")
                        .value("bank recalled the transfer"))
                .andExpect(jsonPath("$.allocations[0].reversedAt").isNotEmpty());

        // Invoice balance is restored (the invoice service derives it from
        // ACTIVE allocations only):
        assertInvoiceState(jmx, officerSession, invoiceId, "UNPAID", "300.00");

        // Everything is PRESERVED in the database - no deletes, no edits:
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, reversed_at, reversed_by, reversal_reason FROM payments "
                        + "WHERE id = ?", paymentId);
        assertThat(row.get("status")).isEqualTo("REVERSED");
        assertThat(row.get("reversed_at")).isNotNull();
        assertThat(((Number) row.get("reversed_by")).longValue()).isEqualTo(admin);
        assertThat(row.get("reversal_reason")).isEqualTo("bank recalled the transfer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE id = ?",
                Long.class, paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_verifications WHERE payment_id = ?",
                Long.class, paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ?",
                Long.class, paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ? "
                        + "AND status = 'ACTIVE'", Long.class, paymentId)).isZero();

        java.util.Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, reason, before_data->>'status' AS before_status, "
                        + "after_data->>'status' AS after_status, "
                        + "after_data->>'reversed_allocations' AS reversed "
                        + "FROM audit_logs WHERE action = 'PAYMENT_REVERSED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1", paymentId);
        assertThat(audit.get("actor_user_id")).isEqualTo(admin);
        assertThat(audit.get("reason")).isEqualTo("bank recalled the transfer");
        assertThat(audit.get("before_status")).isEqualTo("VERIFIED");
        assertThat(audit.get("after_status")).isEqualTo("REVERSED");
        assertThat(audit.get("reversed")).isEqualTo("1");
        assertThat(countAudit("PAYMENT_REVERSED", paymentId))
                .isEqualTo(reverseAuditBefore + 1);

        // REVERSED is terminal - no second reversal, no decision, no
        // further allocation:
        mockMvc.perform(reversePayment(jmx, paymentId, adminSession, adminToken,
                        "{\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("only VERIFIED payments can be reversed"));
        mockMvc.perform(verifyPayment(jmx, paymentId, verifierSession, verifierToken, "{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(allocatePayment(jmx, paymentId, officerSession, officerToken, uniqueKey(),
                        "{\"allocations\":[{\"invoiceId\":" + invoiceId + ",\"amount\":1.00}]}"))
                .andExpect(status().isConflict());

        // There is deliberately no DELETE route on payment paths:
        MvcResult deleteAttempt = mockMvc.perform(delete(
                        "/api/v1/companies/" + jmx + "/payments/" + paymentId)
                        .session(adminSession).header("X-XSRF-TOKEN", adminToken))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"))
                .andReturn();
        assertThat(deleteAttempt.getResponse().getHeader("Allow")).isEqualTo("GET");
        assertThat(admin).isNotEqualTo(verifier);
    }

    // ------------------------------------------------------------------
    // Company isolation
    // ------------------------------------------------------------------

    @Test
    void paymentsAreCompanyScopedAndIsolatedAcrossCompanies() throws Exception {
        long jmxAdmin = roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        MockHttpSession jmxAdminSession = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String jmxAdminToken = csrfToken(jmxAdminSession);

        // A KAM payment recorded by the KAM company admin:
        long kamAdmin = roleMember(COADMIN_KAM_EMAIL, "COMPANY_ADMIN", "KAM");
        MockHttpSession kamAdminSession = login(COADMIN_KAM_EMAIL, ACTOR_PASSWORD);
        String kamAdminToken = csrfToken(kamAdminSession);
        long kam = companyId("KAM");
        long jmx = companyId("JMX");
        long kamCustomer = newCustomer("KAM", "KAM Isolation Payer", "ACTIVE");
        long kamPayment = recordPayment(kam, kamAdminSession, kamAdminToken, kamCustomer,
                "700.00", "CASH", "2026-05-10");
        assertThat(jmxAdmin).isNotEqualTo(kamAdmin);

        // The SAME id through the JMX path is indistinguishable from a
        // nonexistent payment: 404, no data, no "wrong company" hint.
        mockMvc.perform(getPayment(jmx, kamPayment, jmxAdminSession))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(verifyPayment(jmx, kamPayment, jmxAdminSession, jmxAdminToken, "{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(rejectPayment(jmx, kamPayment, jmxAdminSession, jmxAdminToken,
                        "{\"reason\":\"cross\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(allocatePayment(jmx, kamPayment, jmxAdminSession, jmxAdminToken,
                        uniqueKey(), "{\"allocations\":[{\"invoiceId\":1,\"amount\":1.00}]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(reversePayment(jmx, kamPayment, jmxAdminSession, jmxAdminToken,
                        "{\"reason\":\"cross\"}"))
                .andExpect(status().isNotFound());

        // Recording against a customer of the other company is 404:
        mockMvc.perform(recordPayment(jmx, jmxAdminSession, jmxAdminToken,
                        "{\"customerId\":" + kamCustomer + ",\"amount\":10.00,\"method\":\"CASH\","
                                + "\"receivedAt\":\"2026-05-10\"}", uniqueKey()))
                .andExpect(status().isNotFound());

        // It never appears in the JMX list; the KAM row is untouched:
        mockMvc.perform(listPayments(jmx, jmxAdminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(listPayments(kam, jmxAdminSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(recordPayment(kam, jmxAdminSession, jmxAdminToken,
                        "{\"customerId\":1,\"amount\":10.00,\"method\":\"CASH\","
                                + "\"receivedAt\":\"2026-05-10\"}", uniqueKey()))
                .andExpect(status().isForbidden());
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT company_id, status, amount, payment_number FROM payments WHERE id = ?",
                kamPayment);
        assertThat(((Number) row.get("company_id")).longValue()).isEqualTo(kam);
        assertThat(row.get("status")).isEqualTo("PENDING_VERIFICATION");
        assertThat(new BigDecimal(row.get("amount").toString()))
                .isEqualByComparingTo(new BigDecimal("700.00"));

        // The platform admin may read BOTH companies (bypass, no membership):
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession platformSession = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        mockMvc.perform(listPayments(kam, platformSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(kamPayment));
        mockMvc.perform(listPayments(jmx, platformSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------
    // List: bounded pagination, filters, search - all company-scoped
    // ------------------------------------------------------------------

    @Test
    void listIsPaginatedFilteredAndCompanyScoped() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        roleMember(VERIFIER_JMX_EMAIL, "PAYMENT_VERIFIER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        MockHttpSession verifierSession = login(VERIFIER_JMX_EMAIL, ACTOR_PASSWORD);
        String officerToken = csrfToken(officerSession);
        String verifierToken = csrfToken(verifierSession);
        long jmx = companyId("JMX");
        long customerA = newCustomer("JMX", "Filter Payer A", "ACTIVE");
        long customerB = newCustomer("JMX", "Filter Payer B", "ACTIVE");

        long p1 = recordPayment(jmx, officerSession, officerToken, customerA, "100.00", "CASH",
                "2026-01-01", "REF-ALPHA-1", "alpha note one");
        long p2 = recordPayment(jmx, officerSession, officerToken, customerA, "200.00", "CASH",
                "2026-02-01", "REF-ALPHA-2", null);
        long p3 = recordPayment(jmx, officerSession, officerToken, customerB, "300.00", "CASH",
                "2026-03-01", "REF-BRAVO-1", null);
        long p4 = recordPayment(jmx, officerSession, officerToken, customerB, "400.00", "CASH",
                "2026-04-01", "REF-BRAVO-2", null);

        mockMvc.perform(verifyPayment(jmx, p2, verifierSession, verifierToken, "{}"))
                .andExpect(status().isOk());
        mockMvc.perform(rejectPayment(jmx, p3, verifierSession, verifierToken,
                        "{\"reason\":\"wrong amount\"}"))
                .andExpect(status().isOk());

        // Status filter (only stored lifecycle statuses):
        mockMvc.perform(listPayments(jmx, officerSession).param("status", "PENDING_VERIFICATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(listPayments(jmx, officerSession).param("status", "VERIFIED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(p2));
        mockMvc.perform(listPayments(jmx, officerSession).param("status", "REJECTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(p3));
        mockMvc.perform(listPayments(jmx, officerSession).param("status", "REVERSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Customer filter (company-scoped: a foreign id matches nothing):
        mockMvc.perform(listPayments(jmx, officerSession)
                        .param("customerId", String.valueOf(customerA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        long foreignCustomerId = newCustomer("KAM", "KAM Foreign Filter Payer", "ACTIVE");
        mockMvc.perform(listPayments(jmx, officerSession)
                        .param("customerId", String.valueOf(foreignCustomerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // Search covers payment number, reference and notes:
        mockMvc.perform(listPayments(jmx, officerSession).param("q", "REF-ALPHA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(listPayments(jmx, officerSession).param("q", "alpha note one"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(p1));
        String p4Number = jdbc.queryForObject(
                "SELECT payment_number FROM payments WHERE id = ?", String.class, p4);
        mockMvc.perform(listPayments(jmx, officerSession).param("q", p4Number))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(p4));

        // Bounded pagination: 4 payments, 3 per page -> 2 pages, ordered by
        // received_at DESC then id DESC (distinct dates here):
        mockMvc.perform(listPayments(jmx, officerSession).param("page", "0")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(p4))
                .andExpect(jsonPath("$.items[1].id").value(p3))
                .andExpect(jsonPath("$.items[2].id").value(p2));
        mockMvc.perform(listPayments(jmx, officerSession).param("page", "1")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(p1));

        // A KAM payment must never leak into the JMX list or its search:
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession platformSession = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String platformToken = csrfToken(platformSession);
        long kamCustomer = newCustomer("KAM", "KAM Filter Payer", "ACTIVE");
        recordPayment(companyId("KAM"), platformSession, platformToken, kamCustomer, "50.00",
                "CASH", "2026-05-01", "REF-KAM-ONLY", null);
        mockMvc.perform(listPayments(jmx, officerSession).param("q", "REF-KAM-ONLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(officer).isPositive();
    }

    // ------------------------------------------------------------------
    // Authentication / CSRF / no edit or delete routes
    // ------------------------------------------------------------------

    @Test
    void unauthenticatedAndCsrfRulesApplyToPayments() throws Exception {
        mockMvc.perform(listPayments(companyId("JMX"), null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));

        String body = "{\"customerId\":1,\"amount\":10.00,\"method\":\"CASH\","
                + "\"receivedAt\":\"2026-05-10\"}";

        // Unsafe methods without a session are refused by CSRF first:
        mockMvc.perform(post("/api/v1/companies/" + companyId("JMX") + "/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", uniqueKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        // Authenticated but no CSRF token: still refused:
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Csrf Payer", "ACTIVE");
        String json = "{\"customerId\":" + customer
                + ",\"amount\":10.00,\"method\":\"CASH\",\"receivedAt\":\"2026-05-10\"}";
        mockMvc.perform(post("/api/v1/companies/" + jmx + "/payments")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(json).header("Idempotency-Key", uniqueKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(paymentCount(jmx)).isZero();

        long paymentId = recordPayment(jmx, session, csrfToken(session), customer, "10.00",
                "CASH", "2026-05-10");
        mockMvc.perform(post("/api/v1/companies/" + jmx + "/payments/" + paymentId + "/verify")
                        .session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/companies/" + jmx + "/payments/" + paymentId
                        + "/allocations").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocations\":[{\"invoiceId\":1,\"amount\":1.00}]}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ?",
                Long.class, paymentId)).isZero();
    }

    @Test
    void noEditOrDeleteRoutesExistOnPaymentPaths() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "NoDelete Payer", "ACTIVE");
        long paymentId = recordPayment(jmx, session, token, customer, "10.00", "CASH",
                "2026-05-10");
        String collection = "/api/v1/companies/" + jmx + "/payments";
        String detail = collection + "/" + paymentId;
        assertThat(officer).isPositive();

        // The collection supports exactly GET + POST:
        MvcResult deleteCollection = mockMvc.perform(delete(collection).session(session)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();
        assertThat(deleteCollection.getResponse().getHeader("Allow")).isEqualTo("GET, POST");
        mockMvc.perform(patch(collection).session(session).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(collection).session(session).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        // The detail path supports exactly GET (decisions are POST sub-paths):
        MvcResult deleteDetail = mockMvc.perform(delete(detail).session(session)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();
        assertThat(deleteDetail.getResponse().getHeader("Allow")).isEqualTo("GET");
        mockMvc.perform(patch(detail).session(session).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"x\"}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(detail).session(session).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"x\"}"))
                .andExpect(status().isMethodNotAllowed());

        // Sub-paths are POST-only:
        MvcResult deleteVerify = mockMvc.perform(delete(detail + "/verify").session(session)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();
        assertThat(deleteVerify.getResponse().getHeader("Allow")).isEqualTo("POST");
        mockMvc.perform(delete(detail + "/reverse").session(session)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(detail + "/allocations").session(session)
                        .header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed());

        // The payment row still exists exactly once, untouched:
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE id = ?",
                Long.class, paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?",
                String.class, paymentId)).isEqualTo("PENDING_VERIFICATION");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Creates a payment through the API and returns its id. */
    private long recordPayment(long companyId, MockHttpSession session, String token,
            long customerId, String amount, String method, String receivedAt) throws Exception {
        return recordPayment(companyId, session, token, customerId, amount, method, receivedAt,
                null, null);
    }

    private long recordPayment(long companyId, MockHttpSession session, String token,
            long customerId, String amount, String method, String receivedAt,
            String reference, String notes) throws Exception {
        StringBuilder body = new StringBuilder("{\"customerId\":" + customerId
                + ",\"amount\":" + amount + ",\"method\":\"" + method
                + "\",\"receivedAt\":\"" + receivedAt + "\"");
        if (reference != null) {
            body.append(",\"referenceNumber\":\"").append(reference).append('"');
        }
        if (notes != null) {
            body.append(",\"notes\":\"").append(notes).append('"');
        }
        body.append('}');
        String response = mockMvc.perform(recordPayment(companyId, session, token, body.toString(),
                        uniqueKey()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return decimal(response, "$.payment.id").longValue();
    }

    /** Records AND verifies a payment (two different actors, like production). */
    private long recordAndVerify(long companyId, MockHttpSession officerSession,
            String officerToken, MockHttpSession verifierSession, String verifierToken,
            long customerId, String amount) throws Exception {
        long paymentId = recordPayment(companyId, officerSession, officerToken, customerId, amount,
                "CASH", "2026-05-10");
        mockMvc.perform(verifyPayment(companyId, paymentId, verifierSession, verifierToken, "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("VERIFIED"));
        return paymentId;
    }

    /** Creates a DRAFT through the API and returns its id. */
    private long createDraft(long companyId, MockHttpSession session, String token,
            long customerId, String itemsJson) throws Exception {
        String body = "{\"customerId\":" + customerId
                + ",\"issueDate\":\"2026-03-01\",\"dueDate\":\"2099-03-31\","
                + "\"items\":" + itemsJson + "}";
        String response = mockMvc.perform(createInvoice(companyId, session, token, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return decimal(response, "$.id").longValue();
    }

    /** Creates and immediately issues a DRAFT; returns the invoice id. */
    private long createIssued(long companyId, MockHttpSession session, String token,
            long customerId, String itemsJson) throws Exception {
        long invoiceId = createDraft(companyId, session, token, customerId, itemsJson);
        mockMvc.perform(issueInvoice(companyId, invoiceId, session, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        return invoiceId;
    }

    /** Asserts the invoice's server-derived state (the invoice service rule). */
    private void assertInvoiceState(long companyId, MockHttpSession session, long invoiceId,
            String derivedStatus, String outstanding) throws Exception {
        mockMvc.perform(getInvoice(companyId, invoiceId, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedStatus").value(derivedStatus));
        String detail = mockMvc.perform(getInvoice(companyId, invoiceId, session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(decimal(detail, "$.outstandingAmount"))
                .as("invoice %d outstanding", invoiceId)
                .isEqualByComparingTo(outstanding);
    }

    private void assertRecordBadRequest(long companyId, MockHttpSession session, String token,
            String json, String expectedMessage) throws Exception {
        mockMvc.perform(recordPayment(companyId, session, token, json, uniqueKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(expectedMessage));
    }

    private long newCustomer(String companyCode, String name, String status) {
        jdbc.update("""
                INSERT INTO customers (company_id, display_name, status)
                SELECT id, ?, ? FROM companies WHERE code = ?
                """, name, status, companyCode);
        return jdbc.queryForObject(
                "SELECT id FROM customers WHERE display_name = ? AND company_id = "
                        + "(SELECT id FROM companies WHERE code = ?)",
                Long.class, name, companyCode);
    }

    private long paymentCount(long companyId) {
        return jdbc.queryForObject("SELECT count(*) FROM payments WHERE company_id = ?",
                Long.class, companyId);
    }

    private long activeAllocationCount(long paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_allocations WHERE payment_id = ? "
                        + "AND status = 'ACTIVE'", Long.class, paymentId);
    }

    private long keyRows(long companyId, String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_keys WHERE company_id = ? "
                        + "AND idempotency_key = ?", Long.class, companyId, key);
    }

    /** Run-relative audit baseline (audit rows survive across runs). */
    private long countAudit(String action, long entityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = ? AND entity_id = ?",
                Long.class, action, entityId);
    }

    private long nextPaymentSequence(String companyCode) {
        return jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'PAYMENT' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = ?)",
                Long.class, companyCode);
    }

    private static String uniqueKey() {
        return "pay-" + UUID.randomUUID();
    }

    /**
     * Response money/id fields are parsed via BigDecimal (exact contract).
     * The explicit {@code Object} local is required: without it, generic
     * inference on {@code String.valueOf(JsonPath.read(...))} resolves to the
     * {@code char[]} overload and blows up at runtime.
     */
    private static BigDecimal decimal(String responseBody, String path) {
        Object value = JsonPath.read(responseBody, path);
        return new BigDecimal(String.valueOf(value));
    }

    private MockHttpServletRequestBuilder listPayments(long companyId, MockHttpSession session) {
        MockHttpServletRequestBuilder request = get(
                "/api/v1/companies/" + companyId + "/payments");
        if (session != null) {
            request.session(session);
        }
        return request;
    }

    private MockHttpServletRequestBuilder getPayment(long companyId, long paymentId,
            MockHttpSession session) {
        return get("/api/v1/companies/" + companyId + "/payments/" + paymentId)
                .session(session);
    }

    private MockHttpServletRequestBuilder getInvoice(long companyId, long invoiceId,
            MockHttpSession session) {
        return get("/api/v1/companies/" + companyId + "/invoices/" + invoiceId)
                .session(session);
    }

    private MockHttpServletRequestBuilder recordPayment(long companyId, MockHttpSession session,
            String csrfToken, String json, String idempotencyKey) {
        MockHttpServletRequestBuilder request = post(
                "/api/v1/companies/" + companyId + "/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (session != null) {
            request.session(session);
        }
        if (csrfToken != null) {
            request.header("X-XSRF-TOKEN", csrfToken);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request;
    }

    private MockHttpServletRequestBuilder verifyPayment(long companyId, long paymentId,
            MockHttpSession session, String csrfToken, String json) {
        return postToPayment(companyId, paymentId, "verify", session, csrfToken, json);
    }

    private MockHttpServletRequestBuilder rejectPayment(long companyId, long paymentId,
            MockHttpSession session, String csrfToken, String json) {
        return postToPayment(companyId, paymentId, "reject", session, csrfToken, json);
    }

    private MockHttpServletRequestBuilder allocatePayment(long companyId, long paymentId,
            MockHttpSession session, String csrfToken, String idempotencyKey, String json) {
        MockHttpServletRequestBuilder request = postToPayment(companyId, paymentId,
                "allocations", session, csrfToken, json);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request;
    }

    private MockHttpServletRequestBuilder reversePayment(long companyId, long paymentId,
            MockHttpSession session, String csrfToken, String json) {
        return postToPayment(companyId, paymentId, "reverse", session, csrfToken, json);
    }

    private MockHttpServletRequestBuilder postToPayment(long companyId, long paymentId,
            String action, MockHttpSession session, String csrfToken, String json) {
        MockHttpServletRequestBuilder request = post(
                "/api/v1/companies/" + companyId + "/payments/" + paymentId + "/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (session != null) {
            request.session(session);
        }
        if (csrfToken != null) {
            request.header("X-XSRF-TOKEN", csrfToken);
        }
        return request;
    }

    private MockHttpServletRequestBuilder createInvoice(long companyId, MockHttpSession session,
            String csrfToken, String json) {
        MockHttpServletRequestBuilder request = post(
                "/api/v1/companies/" + companyId + "/invoices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (session != null) {
            request.session(session);
        }
        if (csrfToken != null) {
            request.header("X-XSRF-TOKEN", csrfToken);
        }
        return request;
    }

    private MockHttpServletRequestBuilder issueInvoice(long companyId, long invoiceId,
            MockHttpSession session, String csrfToken) {
        MockHttpServletRequestBuilder request = post(
                "/api/v1/companies/" + companyId + "/invoices/" + invoiceId + "/issue");
        if (session != null) {
            request.session(session);
        }
        if (csrfToken != null) {
            request.header("X-XSRF-TOKEN", csrfToken);
        }
        return request;
    }

    private MockHttpServletRequestBuilder voidInvoice(long companyId, long invoiceId,
            MockHttpSession session, String csrfToken, String json) {
        MockHttpServletRequestBuilder request = post(
                "/api/v1/companies/" + companyId + "/invoices/" + invoiceId + "/void")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (session != null) {
            request.session(session);
        }
        if (csrfToken != null) {
            request.header("X-XSRF-TOKEN", csrfToken);
        }
        return request;
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(
                        org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    /** Creates or resets a fixture user and grants the given role. */
    private long roleMember(String email, String roleCode, String companyCode) {
        Long existing = jdbc.query("SELECT id FROM users WHERE email = ?",
                (rs, rowNum) -> rs.getLong(1), email).stream().findFirst().orElse(null);
        long userId;
        if (existing == null) {
            userId = insertUser(email, ACTOR_PASSWORD, "ACTIVE", false);
        } else {
            jdbc.update("""
                    UPDATE users
                    SET password_hash = ?, status = 'ACTIVE', is_platform_admin = FALSE,
                        updated_at = now()
                    WHERE id = ?
                    """, passwordEncoder.encode(ACTOR_PASSWORD), existing);
            userId = existing;
        }
        Integer memberships = jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Integer.class, userId, companyId(companyCode));
        if (memberships == null || memberships == 0) {
            addMembership(userId, companyCode, roleCode, "ACTIVE");
        }
        return userId;
    }

    /** Creates or resets a fixture user; the flag is written ONLY here (test DB). */
    private long ensureFixtureUser(String email, boolean platformAdmin) {
        Long existing = jdbc.query("SELECT id FROM users WHERE email = ?",
                (rs, rowNum) -> rs.getLong(1), email).stream().findFirst().orElse(null);
        if (existing == null) {
            return insertUser(email, ACTOR_PASSWORD, "ACTIVE", platformAdmin);
        }
        jdbc.update("""
                UPDATE users
                SET password_hash = ?, status = 'ACTIVE', is_platform_admin = ?,
                    full_name = 'Fixture Actor', updated_at = now()
                WHERE id = ?
                """, passwordEncoder.encode(ACTOR_PASSWORD), platformAdmin, existing);
        return existing;
    }
}
