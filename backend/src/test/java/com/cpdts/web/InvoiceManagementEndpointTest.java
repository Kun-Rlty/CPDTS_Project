package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cpdts.service.InvoiceManagementService;
import com.cpdts.support.CpdtsIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * API-level tests for company-scoped invoice management (PRD FR-04):
 *
 * <ul>
 *   <li>permissions: RECORD_VIEW for reads, INVOICE_MANAGE for mutations,
 *       platform admin in both companies, company admin only their own;</li>
 *   <li>company isolation: other companies' invoices are 404 through my
 *       path (no disclosure), cross-company writes are 403;</li>
 *   <li>server-side numbering from number_sequences and server-side totals
 *       (client number/total/currency/status never trusted);</li>
 *   <li>BigDecimal HALF_UP line rounding proven EQUAL to the V2 database
 *       generated column, with fixtures where HALF_UP differs from
 *       banker's rounding;</li>
 *   <li>lifecycle: DRAFT editable, ISSUED immutable, VOIDED terminal with a
 *       mandatory reason, void blocked by ACTIVE allocations;</li>
 *   <li>derived states (UNPAID / PARTIALLY_PAID / PAID / OVERDUE) computed
 *       on read - never stored;</li>
 *   <li>active-customer rules for creation and issuance with historical
 *       access preserved for inactive customers;</li>
 *   <li>bounded company-scoped pagination, filters, escaped search, CSRF,
 *       no DELETE routes.</li>
 * </ul>
 *
 * <p>All mutations run only against {@code cpdts_test} (guarded by the base
 * class). Money assertions read the DATABASE value as the authority and the
 * response body as the contract.
 */
class InvoiceManagementEndpointTest extends CpdtsIntegrationTest {

    private static final String ACTOR_PASSWORD = "fixture actor password 1";

    // Fixed fixture emails on purpose: invoice mutations are audited and the
    // audit actor can never be deleted (append-only audit_logs FK), so a
    // bounded set of reusable actors keeps the test database bounded.
    private static final String PLATFORM_ACTOR_EMAIL = "platform.actor@example.test";
    private static final String COADMIN_JMX_EMAIL = "coadmin.jmx.actor@example.test";
    private static final String OFFICER_JMX_EMAIL = "officer.jmx.invoice@example.test";
    private static final String VIEWER_JMX_EMAIL = "viewer.jmx.invoice@example.test";

    @BeforeEach
    void startWithNoInvoices() {
        // Full financial-state reset: TRUNCATE customers CASCADE empties
        // invoices, invoice_items, payments, allocations and verifications
        // (the only legal cleanup path around the no-delete triggers).
        clearTestCustomers();
    }

    @AfterEach
    void cleanUpTestUsers() {
        deleteAllTestUsers();
    }

    // ------------------------------------------------------------------
    // Create: server number, server total, atomic audit, forged fields
    // ------------------------------------------------------------------

    @Test
    void financeOfficerCreatesDraftWithServerNumberTotalAndAudit() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Alpha Traders Ltd", "ACTIVE");

        long sequenceBefore = nextSequence("JMX");
        long kamSequenceBefore = nextSequence("KAM");

        // invoiceNumber / totalAmount / currency / status / id / companyId in
        // the body must be IGNORED (they are absent from the DTO):
        String body = """
                {"customerId":%d,"issueDate":"2026-03-01","dueDate":"2026-03-31",
                 "description":"March supply","items":[
                   {"description":"Consulting","quantity":2,"unitPrice":123.45},
                   {"description":"Transport","quantity":1,"unitPrice":50.00}],
                 "invoiceNumber":"FORGED-1","totalAmount":99999999,"currencyCode":"USD",
                 "status":"ISSUED","id":42,"companyId":999999}""".formatted(customer);
        MvcResult created = mockMvc.perform(createInvoice(jmx, session, token, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currencyCode").value("TZS"))
                .andExpect(jsonPath("$.invoiceNumber")
                        .value("JMX-INV-%05d".formatted(sequenceBefore)))
                .andExpect(jsonPath("$.derivedStatus").value("DRAFT"))
                .andExpect(jsonPath("$.outstandingAmount").value(0.00))
                .andExpect(jsonPath("$.items.length()").value(2))
                // (customer_code is nullable in V2 and the direct-SQL fixture
                // leaves it null; the customer JOIN itself is what matters.)
                .andExpect(jsonPath("$.customerName").value("Alpha Traders Ltd"))
                .andReturn();
        long invoiceId = decimal(created.getResponse().getContentAsString(), "$.id").longValue();

        // Database is authoritative: total = sum of the DB's own line totals.
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, total_amount, currency_code, customer_id, invoice_number, "
                        + "created_by FROM invoices WHERE id = ? AND company_id = ?",
                invoiceId, jmx);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("currency_code")).isEqualTo("TZS");
        assertThat(row.get("invoice_number")).isEqualTo("JMX-INV-%05d".formatted(sequenceBefore));
        assertThat(((Number) row.get("customer_id")).longValue()).isEqualTo(customer);
        assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(officer);

        List<BigDecimal> lineTotals = jdbc.queryForList(
                "SELECT line_total FROM invoice_items WHERE invoice_id = ? "
                        + "AND company_id = ? ORDER BY sort_order",
                BigDecimal.class, invoiceId, jmx);
        BigDecimal expectedTotal = new BigDecimal("296.90"); // 246.90 + 50.00
        assertThat(lineTotals).containsExactly(new BigDecimal("246.90"),
                new BigDecimal("50.00"));
        assertThat(new BigDecimal(row.get("total_amount").toString()))
                .as("invoice total must equal the sum of database line totals")
                .isEqualByComparingTo(expectedTotal);
        assertThat(decimal(created.getResponse().getContentAsString(), "$.totalAmount"))
                .as("response total must match the stored total")
                .isEqualByComparingTo(expectedTotal);

        // Sequence advanced exactly once, company-scoped (run-relative:
        // counters persist across runs in the scratch test database):
        assertThat(nextSequence("JMX")).isEqualTo(sequenceBefore + 1);
        assertThat(nextSequence("KAM")).isEqualTo(kamSequenceBefore)
                .as("the KAM counter must be untouched by a JMX creation");

        // Atomic audit event with the session actor and a minimized summary:
        java.util.Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, company_id, entity_type, "
                        + "after_data->>'invoice_number' AS number, "
                        + "after_data->>'total_amount' AS total, "
                        + "after_data->>'item_count' AS items, "
                        + "after_data->>'status' AS status, after_data::text AS data "
                        + "FROM audit_logs WHERE action = 'INVOICE_CREATED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1", invoiceId);
        assertThat(audit.get("actor_user_id")).isEqualTo(officer);
        assertThat(audit.get("company_id")).isEqualTo(jmx);
        assertThat(audit.get("entity_type")).isEqualTo("INVOICE");
        assertThat(audit.get("number")).isEqualTo("JMX-INV-%05d".formatted(sequenceBefore));
        assertThat(audit.get("total")).isEqualTo("296.90");
        assertThat(audit.get("items")).isEqualTo("2");
        assertThat(audit.get("status")).isEqualTo("DRAFT");
        assertThat((String) audit.get("data"))
                .as("audit data is minimized - never the full item list")
                .doesNotContain("Consulting").doesNotContain("Transport");
    }

    @Test
    void halfUpLineRoundingMatchesTheDatabaseGeneratedColumn() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");

        // Fixtures where HALF_UP differs from banker's rounding:
        //   0.125 -> 0.13 (banker's: 0.12), 0.625 -> 0.63 (banker's: 0.62).
        // PostgreSQL round(numeric, 2) rounds half AWAY from zero, so the
        // backend's HALF_UP must produce exactly the stored line totals.
        long customer = newCustomer("JMX", "Rounding Co", "ACTIVE");
        String body = """
                {"customerId":%d,"issueDate":"2026-04-01","dueDate":"2026-04-30","items":[
                   {"description":"A","quantity":0.125,"unitPrice":1.00},
                   {"description":"B","quantity":0.625,"unitPrice":1.00},
                   {"description":"C","quantity":3,"unitPrice":123.45},
                   {"description":"D","quantity":0.001,"unitPrice":0.01}]}""".formatted(customer);
        MvcResult created = mockMvc.perform(createInvoice(jmx, session, token, body))
                .andExpect(status().isCreated())
                .andReturn();
        long invoiceId = decimal(created.getResponse().getContentAsString(), "$.id").longValue();

        // The DATABASE's generated values (authority for this assertion):
        List<BigDecimal> dbLines = jdbc.queryForList(
                "SELECT line_total FROM invoice_items WHERE invoice_id = ? "
                        + "AND company_id = ? ORDER BY sort_order",
                BigDecimal.class, invoiceId, jmx);
        assertThat(dbLines).containsExactly(
                new BigDecimal("0.13"),   // 0.125  HALF_UP (banker's 0.12)
                new BigDecimal("0.63"),   // 0.625  HALF_UP (banker's 0.62)
                new BigDecimal("370.35"), // exact
                new BigDecimal("0.00"));  // 0.00001 -> 0.00

        // The service's own computation (BigDecimal HALF_UP, then sum):
        BigDecimal serviceTotal = InvoiceManagementService.lineTotal(
                new BigDecimal("0.125"), new BigDecimal("1.00"))
                .add(InvoiceManagementService.lineTotal(
                        new BigDecimal("0.625"), new BigDecimal("1.00")))
                .add(InvoiceManagementService.lineTotal(
                        new BigDecimal("3"), new BigDecimal("123.45")))
                .add(InvoiceManagementService.lineTotal(
                        new BigDecimal("0.001"), new BigDecimal("0.01")));
        BigDecimal dbTotal = jdbc.queryForObject(
                "SELECT total_amount FROM invoices WHERE id = ?", BigDecimal.class, invoiceId);
        assertThat(dbTotal).isEqualByComparingTo(serviceTotal);
        assertThat(dbTotal).isEqualByComparingTo(new BigDecimal("371.11"));
        assertThat(decimal(created.getResponse().getContentAsString(), "$.totalAmount"))
                .isEqualByComparingTo(dbTotal);
    }

    // ------------------------------------------------------------------
    // Validation against the V2 schema
    // ------------------------------------------------------------------

    @Test
    void invalidInputIsRejectedWithoutCreatingRows() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Validation Co", "ACTIVE");
        long otherCustomer = newCustomer("JMX", "Inactive Co", "INACTIVE");
        long foreignCustomer = newCustomer("KAM", "KAM Customer", "ACTIVE");

        // Missing / unknown / foreign / inactive customer:
        assertBadRequestCreate(jmx, session, token,
                "{\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}");
        // Unknown customer id in my own company: 404, like the customer API
        // (indistinguishable from a nonexistent customer - no probing).
        mockMvc.perform(createInvoice(jmx, session, token,
                        "{\"customerId\":999999999,\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\","
                                + "\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(createInvoice(jmx, session, token,
                        "{\"customerId\":" + foreignCustomer
                                + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\","
                                + "\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(createInvoice(jmx, session, token,
                        "{\"customerId\":" + otherCustomer
                                + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\","
                                + "\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));

        // Dates: missing, malformed, out-of-range, due before issue:
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"01/03/2026\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"1999-01-01\",\"dueDate\":\"1999-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-02-01\",\"dueDate\":\"2026-01-01\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}");

        // Items: empty, missing description, bad quantity/price/precision:
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\"}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"\",\"quantity\":1,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":0,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":-1,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1.2345,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.005}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":-0.01}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1000000000,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1000000000000}]}");

        // Field bounds: item description 500, invoice description 1000:
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\""
                        + "d".repeat(501) + "\",\"quantity\":1,\"unitPrice\":1.00}]}");
        assertBadRequestCreate(jmx, session, token,
                "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\",\"description\":\""
                        + "d".repeat(1001) + "\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}");

        // At the bounds everything is still accepted:
        // ...but all bounds fitting can still overflow the NUMERIC(14,2)
        // total (999999999.999 x 999999999999.99): refused.
        mockMvc.perform(createInvoice(jmx, session, token,
                        "{\"customerId\":" + customer + ",\"issueDate\":\"2026-01-01\","
                                + "\"dueDate\":\"2026-02-01\",\"description\":\""
                                + "d".repeat(1000) + "\",\"items\":[{\"description\":\""
                                + "d".repeat(500) + "\",\"quantity\":999999999.999,"
                                + "\"unitPrice\":999999999999}]}"))
                .andExpect(status().isBadRequest());

        // Pagination bounds and filter validation:
        long customerId = newCustomer("JMX", "Paging Co", "ACTIVE");
        mockMvc.perform(listInvoices(jmx, session).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(listInvoices(jmx, session).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(listInvoices(jmx, session).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(listInvoices(jmx, session).param("status", "BANANA"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(listInvoices(jmx, session).param("customerId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        // Unknown company fails closed; non-numeric path is a 400:
        mockMvc.perform(listInvoices(999_999_999L, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/api/v1/companies/abc/invoices").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE company_id = ?",
                Long.class, jmx)).isZero()
                .as("no invoice may exist beyond accepted requests (none here)");
        assertThat(customerId).isPositive();
    }

    // ------------------------------------------------------------------
    // Lifecycle: issue, immutability, positive total, active customer
    // ------------------------------------------------------------------

    @Test
    void issueMovesDraftToIssuedAndIssuedInvoicesAreImmutable() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Lifecycle Co", "ACTIVE");
        long invoiceId = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Work\",\"quantity\":2,\"unitPrice\":100.00}]");

        // DRAFT -> ISSUED: issued_at set, derived status flips, audit diff:
        MvcResult issued = mockMvc.perform(issueInvoice(jmx, invoiceId, session, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.issuedAt").isNotEmpty())
                .andExpect(jsonPath("$.outstandingAmount").value(200.00))
                .andReturn();
        assertThat(decimal(issued.getResponse().getContentAsString(), "$.totalAmount"))
                .isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(jdbc.queryForObject(
                "SELECT issued_at IS NOT NULL FROM invoices WHERE id = ?", Boolean.class,
                invoiceId)).isTrue();

        java.util.Map<String, Object> issueAudit = jdbc.queryForMap(
                "SELECT before_data->>'status' AS before_status, "
                        + "after_data->>'status' AS after_status "
                        + "FROM audit_logs WHERE action = 'INVOICE_ISSUED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1", invoiceId);
        assertThat(issueAudit.get("before_status")).isEqualTo("DRAFT");
        assertThat(issueAudit.get("after_status")).isEqualTo("ISSUED");

        // ISSUED is immutable: edit, re-issue and item changes are refused:
        mockMvc.perform(patchInvoice(jmx, invoiceId, session, token,
                        "{\"description\":\"changed\",\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"));
        mockMvc.perform(issueInvoice(jmx, invoiceId, session, token))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "SELECT description FROM invoices WHERE id = ?", String.class, invoiceId))
                .isEqualTo(null);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoice_items WHERE invoice_id = ?", Long.class,
                invoiceId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'INVOICE_UPDATED' "
                        + "AND entity_id = ?", Long.class, invoiceId)).isZero();

        // An ISSUED invoice can be voided (correction path) - see void test.
    }

    @Test
    void issuingRequiresPositiveTotalAndAnActiveCustomer() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");

        // Zero-priced items -> total 0: valid DRAFT, but issue must refuse:
        long customer = newCustomer("JMX", "Free Co", "ACTIVE");
        long zeroTotal = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Freebie\",\"quantity\":1,\"unitPrice\":0.00}]");
        mockMvc.perform(issueInvoice(jmx, zeroTotal, session, token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "invoice total must be positive before it can be issued"));
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?",
                String.class, zeroTotal)).isEqualTo("DRAFT");

        // Customer deactivated AFTER draft creation: issuance refused...
        long customer2 = newCustomer("JMX", "Deactivated Co", "ACTIVE");
        long draft = createDraft(jmx, session, token, customer2,
                "[{\"description\":\"Work\",\"quantity\":1,\"unitPrice\":100.00}]");
        jdbc.update("UPDATE customers SET status = 'INACTIVE', updated_at = now() WHERE id = ?",
                customer2);
        mockMvc.perform(issueInvoice(jmx, draft, session, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("customer is not active"));
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?",
                String.class, draft)).isEqualTo("DRAFT");

        // ...and a NEW draft for the inactive customer is refused (409):
        mockMvc.perform(createInvoice(jmx, session, token,
                        "{\"customerId\":" + customer2
                                + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\","
                                + "\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("customer is not active"));
    }

    @Test
    void historicalInvoicesOfInactiveCustomersRemainAccessible() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Historical Co", "ACTIVE");
        long invoiceId = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Historical work\",\"quantity\":1,\"unitPrice\":500.00}]");
        // A second DRAFT created while the customer is still active: it must
        // stay editable AFTER deactivation (history keeps working).
        long historicalDraft = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Old draft\",\"quantity\":1,\"unitPrice\":10.00}]");
        mockMvc.perform(issueInvoice(jmx, invoiceId, session, token))
                .andExpect(status().isOk());

        // Deactivate the customer - history must NOT disappear or lock:
        jdbc.update("UPDATE customers SET status = 'INACTIVE', updated_at = now() WHERE id = ?",
                customer);

        mockMvc.perform(getInvoice(jmx, invoiceId, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.customerName").value("Historical Co"));
        // Both historical invoices (the ISSUED one and the still-DRAFT one)
        // remain fully visible for the now-INACTIVE customer:
        mockMvc.perform(listInvoices(jmx, session).param("customerId", String.valueOf(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(listInvoices(jmx, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        // Draft edits and voids on history keep working (only creation and
        // issuance require an active customer):
        mockMvc.perform(patchInvoice(jmx, historicalDraft, session, token,
                        "{\"description\":\"still editable\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(voidInvoice(jmx, invoiceId, session, token,
                        "{\"reason\":\"decommissioned\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));
    }

    // ------------------------------------------------------------------
    // Void: reason, terminal state, allocation coordination
    // ------------------------------------------------------------------

    @Test
    void voidRequiresAReasonAndIsTerminal() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Void Co", "ACTIVE");
        long draft = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Draft work\",\"quantity\":1,\"unitPrice\":75.00}]");
        long issued = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Issued work\",\"quantity\":1,\"unitPrice\":75.00}]");
        mockMvc.perform(issueInvoice(jmx, issued, session, token))
                .andExpect(status().isOk());

        // Reason is mandatory and validated before anything else:
        mockMvc.perform(voidInvoice(jmx, issued, session, token, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("void reason is required"));
        mockMvc.perform(voidInvoice(jmx, issued, session, token, "{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // DRAFTs may be voided (before issuance):
        mockMvc.perform(voidInvoice(jmx, draft, session, token, "{\"reason\":\"created by mistake\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.derivedStatus").value("VOIDED"))
                .andExpect(jsonPath("$.outstandingAmount").value(0.00));

        // ISSUED invoices are voided too (the approved correction path):
        long voidedIssued = decimal(mockMvc.perform(
                        voidInvoice(jmx, issued, session, token,
                                "{\"reason\":\"wrong customer agreed terms\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andReturn().getResponse().getContentAsString(), "$.id").longValue();
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT voided_at, void_reason, status FROM invoices WHERE id = ?", voidedIssued);
        assertThat(row.get("status")).isEqualTo("VOIDED");
        assertThat(row.get("voided_at")).isNotNull();
        assertThat(row.get("void_reason")).isEqualTo("wrong customer agreed terms");

        // The audit event carries the reason:
        java.util.Map<String, Object> audit = jdbc.queryForMap(
                "SELECT reason, before_data->>'status' AS before_status, "
                        + "after_data->>'status' AS after_status "
                        + "FROM audit_logs WHERE action = 'INVOICE_VOIDED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1", voidedIssued);
        assertThat(audit.get("reason")).isEqualTo("wrong customer agreed terms");
        assertThat(audit.get("before_status")).isEqualTo("ISSUED");
        assertThat(audit.get("after_status")).isEqualTo("VOIDED");

        // VOIDED is terminal: no second void, no edit, no issue:
        mockMvc.perform(voidInvoice(jmx, voidedIssued, session, token, "{\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("invoice is already voided"));
        mockMvc.perform(patchInvoice(jmx, voidedIssued, session, token,
                        "{\"description\":\"necro\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(issueInvoice(jmx, voidedIssued, session, token))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE id = ?",
                Long.class, voidedIssued)).isEqualTo(1);

        // There is deliberately no DELETE route on known invoice paths:
        MvcResult deleteAttempt = mockMvc.perform(delete(
                        "/api/v1/companies/" + jmx + "/invoices/" + voidedIssued)
                        .session(session).header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"))
                .andReturn();
        assertThat(deleteAttempt.getResponse().getHeader("Allow")).isEqualTo("GET, PATCH");
    }

    @Test
    void voidIsRejectedWhileActiveAllocationsExist() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long officer = jdbc.queryForObject("SELECT id FROM users WHERE email = ?",
                Long.class, OFFICER_JMX_EMAIL);
        long customer = newCustomer("JMX", "Allocated Co", "ACTIVE");
        long invoiceId = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Work\",\"quantity\":1,\"unitPrice\":300.00}]");
        mockMvc.perform(issueInvoice(jmx, invoiceId, session, token))
                .andExpect(status().isOk());

        // Seed a VERIFIED payment with an ACTIVE allocation (payments phase
        // not built yet - direct SQL fixtures in the scratch test DB):
        long paymentId = seedVerifiedPayment("JMX", customer, "JMX-TPAY-1",
                new BigDecimal("300.00"), officer);
        long allocationId = seedAllocation("JMX", customer, paymentId, invoiceId,
                new BigDecimal("150.00"), officer);

        // Void must be REFUSED while the allocation is active (the service
        // holds the invoice row lock across this check):
        mockMvc.perform(voidInvoice(jmx, invoiceId, session, token, "{\"reason\":\"try me\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("invoice has active payment allocations and cannot be voided"));
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?",
                String.class, invoiceId)).isEqualTo("ISSUED");
        assertThat(jdbc.queryForObject(
                "SELECT voided_at IS NULL FROM invoices WHERE id = ?", Boolean.class,
                invoiceId)).isTrue();

        // Reversing the allocation (approved audited path) unblocks the void:
        reverseAllocation(allocationId, officer);
        mockMvc.perform(voidInvoice(jmx, invoiceId, session, token,
                        "{\"reason\":\"agreed cancellation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));

        // And the database backstop (V7) refuses direct allocation inserts
        // against any non-ISSUED invoice regardless of service code:
        long draft = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Draft\",\"quantity\":1,\"unitPrice\":10.00}]");
        assertThatThrownBy(() -> seedAllocation("JMX", customer, paymentId, draft,
                new BigDecimal("1.00"), officer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .as("V7 trigger must block allocations against DRAFT invoices");
    }

    // ------------------------------------------------------------------
    // Derived states: computed on read, never stored
    // ------------------------------------------------------------------

    @Test
    void derivedStatesAreComputedFromAllocationsAndNeverStored() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long officer = jdbc.queryForObject("SELECT id FROM users WHERE email = ?",
                Long.class, OFFICER_JMX_EMAIL);
        long customer = newCustomer("JMX", "Derived Co", "ACTIVE");

        long unpaid = createIssued(jmx, session, token, customer, "2026-06-01", "2099-06-01",
                "[{\"description\":\"Unpaid\",\"quantity\":1,\"unitPrice\":100.00}]");
        long partial = createIssued(jmx, session, token, customer, "2026-06-01", "2099-06-01",
                "[{\"description\":\"Partial\",\"quantity\":1,\"unitPrice\":100.00}]");
        long paid = createIssued(jmx, session, token, customer, "2026-06-01", "2099-06-01",
                "[{\"description\":\"Paid\",\"quantity\":1,\"unitPrice\":100.00}]");
        long overdue = createIssued(jmx, session, token, customer, "2026-01-01", "2026-01-15",
                "[{\"description\":\"Overdue\",\"quantity\":1,\"unitPrice\":100.00}]");
        long draft = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Draft\",\"quantity\":1,\"unitPrice\":100.00}]");
        long voided = createDraft(jmx, session, token, customer,
                "[{\"description\":\"Voided\",\"quantity\":1,\"unitPrice\":100.00}]");
        mockMvc.perform(voidInvoice(jmx, voided, session, token, "{\"reason\":\"nope\"}"))
                .andExpect(status().isOk());

        long paymentId = seedVerifiedPayment("JMX", customer, "JMX-TPAY-D",
                new BigDecimal("150.00"), officer);
        seedAllocation("JMX", customer, paymentId, partial, new BigDecimal("40.00"), officer);
        seedAllocation("JMX", customer, paymentId, paid, new BigDecimal("100.00"), officer);

        assertDerived(jmx, session, unpaid, "UNPAID", "100.00");
        assertDerived(jmx, session, partial, "PARTIALLY_PAID", "60.00");
        assertDerived(jmx, session, paid, "PAID", "0.00");
        assertDerived(jmx, session, overdue, "OVERDUE", "100.00");
        assertDerived(jmx, session, draft, "DRAFT", "0.00");
        assertDerived(jmx, session, voided, "VOIDED", "0.00");

        // The lifecycle column ONLY ever holds the three stored statuses -
        // derived states are computed, never persisted:
        List<String> stored = jdbc.queryForList(
                "SELECT DISTINCT status FROM invoices WHERE company_id = ? ORDER BY status",
                String.class, jmx);
        assertThat(stored).containsExactly("DRAFT", "ISSUED", "VOIDED");
    }

    // ------------------------------------------------------------------
    // Permissions and company isolation
    // ------------------------------------------------------------------

    @Test
    void viewerCanReadButNotMutate() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        long customer = newCustomer("JMX", "ReadOnly Co", "ACTIVE");
        long invoiceId = createDraft(companyId("JMX"), officerSession,
                csrfToken(officerSession), customer,
                "[{\"description\":\"Work\",\"quantity\":1,\"unitPrice\":50.00}]");
        assertThat(officer).isPositive();

        long viewer = roleMember(VIEWER_JMX_EMAIL, "VIEWER", "JMX");
        MockHttpSession viewerSession = login(VIEWER_JMX_EMAIL, ACTOR_PASSWORD);
        String viewerToken = csrfToken(viewerSession);
        long jmx = companyId("JMX");

        // RECORD_VIEW: reads work...
        mockMvc.perform(listInvoices(jmx, viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(getInvoice(jmx, invoiceId, viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        // ...but INVOICE_MANAGE is missing: every mutation is refused:
        mockMvc.perform(createInvoice(jmx, viewerSession, viewerToken,
                        "{\"customerId\":" + customer
                                + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\","
                                + "\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(patchInvoice(jmx, invoiceId, viewerSession, viewerToken,
                        "{\"description\":\"nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(issueInvoice(jmx, invoiceId, viewerSession, viewerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(voidInvoice(jmx, invoiceId, viewerSession, viewerToken,
                        "{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
        assertThat(viewer).isPositive();
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?",
                String.class, invoiceId)).isEqualTo("DRAFT");
    }

    @Test
    void companyAdminCannotManageInvoicesInTheOtherCompany() throws Exception {
        roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        mockMvc.perform(listInvoices(companyId("KAM"), session))
                .andExpect(status().isForbidden());
        mockMvc.perform(createInvoice(companyId("KAM"), session, token,
                        "{\"customerId\":1,\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\","
                                + "\"items\":[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE company_id = ?",
                Long.class, companyId("KAM"))).isZero()
                .as("nothing may be created in the other company");
    }

    @Test
    void foreignInvoicesAreNotFoundThroughMyPath() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession platformSession = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String platformToken = csrfToken(platformSession);
        long kam = companyId("KAM");
        long kamCustomer = newCustomer("KAM", "KAM Invoice Co", "ACTIVE");
        long kamInvoiceId = createDraft(kam, platformSession, platformToken, kamCustomer,
                "[{\"description\":\"KAM only\",\"quantity\":1,\"unitPrice\":999.00}]");

        roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");

        // The SAME id through the JMX path is indistinguishable from a
        // nonexistent invoice: 404, no data, no "wrong company" hint.
        mockMvc.perform(getInvoice(jmx, kamInvoiceId, session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(patchInvoice(jmx, kamInvoiceId, session, token,
                        "{\"description\":\"cross\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(issueInvoice(jmx, kamInvoiceId, session, token))
                .andExpect(status().isNotFound());
        mockMvc.perform(voidInvoice(jmx, kamInvoiceId, session, token, "{\"reason\":\"cross\"}"))
                .andExpect(status().isNotFound());

        // It never appears in my list, and the KAM row is untouched:
        mockMvc.perform(listInvoices(jmx, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT company_id, status, total_amount FROM invoices WHERE id = ?",
                kamInvoiceId);
        assertThat(((Number) row.get("company_id")).longValue()).isEqualTo(kam);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(new BigDecimal(row.get("total_amount").toString()))
                .isEqualByComparingTo(new BigDecimal("999.00"));
        List<String> itemDescriptions = jdbc.queryForList(
                "SELECT description FROM invoice_items WHERE invoice_id = ? AND company_id = ?",
                String.class, kamInvoiceId, kam);
        assertThat(itemDescriptions).containsExactly("KAM only");
    }

    @Test
    void platformAdminManagesInvoicesInBothCompanies() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long kamCustomer = newCustomer("KAM", "KAM Customer", "ACTIVE");
        long jmxCustomer = newCustomer("JMX", "JMX Customer", "ACTIVE");

        long kamInvoice = createDraft(companyId("KAM"), session, token, kamCustomer,
                "[{\"description\":\"KAM invoice\",\"quantity\":1,\"unitPrice\":10.00}]");
        mockMvc.perform(listInvoices(companyId("JMX"), session))
                .andExpect(status().isOk());
        mockMvc.perform(listInvoices(companyId("KAM"), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        assertThat(jmxCustomer).isPositive();
        assertThat(kamInvoice).isPositive();
    }

    // ------------------------------------------------------------------
    // Pagination + filters, scoped and escaped
    // ------------------------------------------------------------------

    @Test
    void listIsPaginatedFilteredEscapedAndCompanyScoped() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession jmxSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String jmxToken = csrfToken(jmxSession);
        long jmx = companyId("JMX");
        long customerA = newCustomer("JMX", "Filter A", "ACTIVE");
        long customerB = newCustomer("JMX", "Filter B", "ACTIVE");

        // The `q` filter searches the INVOICE's number/description columns,
        // so the distinctive strings go into the invoice description.
        long d1 = createDraft(jmx, jmxSession, jmxToken, customerA,
                "Quarterly fee 100% sure",
                "[{\"description\":\"item\",\"quantity\":1,\"unitPrice\":1.00}]");
        long d2 = createDraft(jmx, jmxSession, jmxToken, customerB,
                "Quarterly fee 1000 sure",
                "[{\"description\":\"item\",\"quantity\":1,\"unitPrice\":1.00}]");
        long d3 = createDraft(jmx, jmxSession, jmxToken, customerA,
                "under_score work",
                "[{\"description\":\"item\",\"quantity\":1,\"unitPrice\":1.00}]");
        long i1 = createDraft(jmx, jmxSession, jmxToken, customerA, "Issued work",
                "[{\"description\":\"item\",\"quantity\":1,\"unitPrice\":1.00}]");
        mockMvc.perform(issueInvoice(jmx, i1, jmxSession, jmxToken))
                .andExpect(status().isOk());

        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession platformSession = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String platformToken = csrfToken(platformSession);
        long kamCustomer = newCustomer("KAM", "KAM Filter", "ACTIVE");
        createDraft(companyId("KAM"), platformSession, platformToken, kamCustomer,
                "Quarterly fee KAM",
                "[{\"description\":\"item\",\"quantity\":1,\"unitPrice\":1.00}]");

        // Status filter:
        mockMvc.perform(listInvoices(jmx, jmxSession).param("status", "DRAFT"))
                .andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(listInvoices(jmx, jmxSession).param("status", "ISSUED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(i1));

        // Customer filter (company-scoped: a foreign customer id matches nothing):
        mockMvc.perform(listInvoices(jmx, jmxSession)
                        .param("customerId", String.valueOf(customerB)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(d2));
        long foreignCustomerId = jdbc.queryForObject(
                "SELECT id FROM customers WHERE company_id = ? LIMIT 1", Long.class,
                companyId("KAM"));
        mockMvc.perform(listInvoices(jmx, jmxSession)
                        .param("customerId", String.valueOf(foreignCustomerId)))
                .andExpect(jsonPath("$.totalElements").value(0));

        // Escaped ILIKE search: '%' and '_' typed by users match literally:
        mockMvc.perform(listInvoices(jmx, jmxSession).param("q", "100%"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(d1));
        mockMvc.perform(listInvoices(jmx, jmxSession).param("q", "under_s"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(d3));
        mockMvc.perform(listInvoices(jmx, jmxSession).param("q", "Quarterly"))
                .andExpect(jsonPath("$.totalElements").value(2));
        // Search also covers invoice numbers:
        String number = jdbc.queryForObject(
                "SELECT invoice_number FROM invoices WHERE id = ?", String.class, i1);
        mockMvc.perform(listInvoices(jmx, jmxSession).param("q", number))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(i1));

        // Bounded pagination: 4 JMX invoices, 3 per page -> pages 3/1,
        // ordered issue_date DESC then id DESC (equal dates -> id DESC):
        mockMvc.perform(listInvoices(jmx, jmxSession).param("page", "0").param("size", "3"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(3));
        mockMvc.perform(listInvoices(jmx, jmxSession).param("page", "1").param("size", "3"))
                .andExpect(jsonPath("$.items.length()").value(1));

        // Search never crosses the company line:
        mockMvc.perform(listInvoices(companyId("KAM"), platformSession).param("q", "Quarterly"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].invoiceNumber").value("KAM-INV-%05d"
                        .formatted(nextSequence("KAM") - 1)));
        assertThat(d1 + d2 + d3).isPositive();
    }

    // ------------------------------------------------------------------
    // Authentication / CSRF
    // ------------------------------------------------------------------

    @Test
    void unauthenticatedAndCsrfRulesApply() throws Exception {
        mockMvc.perform(listInvoices(companyId("JMX"), null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));

        // Unsafe methods without a session are refused by CSRF first:
        mockMvc.perform(createInvoice(companyId("JMX"), null, null,
                        "{\"customerId\":1,\"issueDate\":\"2026-01-01\","
                                + "\"dueDate\":\"2026-02-01\",\"items\":[{\"description\":\"x\","
                                + "\"quantity\":1,\"unitPrice\":1.00}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        // Authenticated but no CSRF token: still refused (issue + void too).
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "Csrf Co", "ACTIVE");
        mockMvc.perform(post("/api/v1/companies/" + jmx + "/invoices")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customer
                                + ",\"issueDate\":\"2026-01-01\",\"dueDate\":\"2026-02-01\","
                                + "\"items\":[{\"description\":\"x\",\"quantity\":1,"
                                + "\"unitPrice\":1.00}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices", Long.class)).isZero();

        long invoiceId = createDraft(jmx, session, csrfToken(session), customer,
                "[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]");
        mockMvc.perform(post("/api/v1/companies/" + jmx + "/invoices/" + invoiceId + "/issue")
                        .session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/companies/" + jmx + "/invoices/" + invoiceId + "/void")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?",
                String.class, invoiceId)).isEqualTo("DRAFT");
    }

    @Test
    void noDeleteRoutesExistOnInvoicePaths() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long customer = newCustomer("JMX", "NoDelete Co", "ACTIVE");
        long invoiceId = createDraft(jmx, session, token, customer,
                "[{\"description\":\"x\",\"quantity\":1,\"unitPrice\":1.00}]");
        String detail = "/api/v1/companies/" + jmx + "/invoices/" + invoiceId;

        MvcResult collection = mockMvc.perform(delete("/api/v1/companies/" + jmx + "/invoices")
                        .session(session).header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();
        assertThat(collection.getResponse().getHeader("Allow")).isEqualTo("GET, POST");
        assertThat(collection.getResponse().getContentAsString())
                .contains("method_not_allowed");

        MvcResult issue = mockMvc.perform(delete(detail + "/issue")
                        .session(session).header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed())
                .andReturn();
        assertThat(issue.getResponse().getHeader("Allow")).isEqualTo("POST");

        // PATCH on the issue sub-path is also method-not-allowed:
        mockMvc.perform(patch(detail + "/issue").session(session)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE id = ?",
                Long.class, invoiceId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void assertDerived(long companyId, MockHttpSession session, long invoiceId,
            String expectedDerived, String expectedOutstanding) throws Exception {
        String detail = mockMvc.perform(getInvoice(companyId, invoiceId, session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedStatus").value(expectedDerived))
                .andReturn().getResponse().getContentAsString();
        // Money must be compared numerically: JsonPath parses 100.00 as a
        // Double, so jsonPath(...).value(new BigDecimal(...)) would compare
        // BigDecimal vs Double and fail on equals() despite equal values.
        assertThat(decimal(detail, "$.outstandingAmount"))
                .as("detail outstanding amount")
                .isEqualByComparingTo(expectedOutstanding);
        // Summary rows (list) must agree with the detail row:
        String list = mockMvc.perform(listInvoices(companyId, session)
                        .param("customerId", String.valueOf(
                                jdbc.queryForObject("SELECT customer_id FROM invoices WHERE id = ?",
                                        Long.class, invoiceId))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Filter in Java instead of a JsonPath filter expression: numeric id
        // comparisons inside [?(...)] are sensitive to Integer/Long parsing.
        List<java.util.Map<String, Object>> rows = JsonPath.read(list, "$.items");
        java.util.Map<String, Object> row = rows.stream()
                .filter(r -> String.valueOf(r.get("id")).equals(String.valueOf(invoiceId)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "invoice " + invoiceId + " missing from its customer's list"));
        assertThat(row.get("derivedStatus")).isEqualTo(expectedDerived);
        assertThat(new BigDecimal(String.valueOf(row.get("outstandingAmount"))))
                .as("list outstanding amount must agree with the detail row")
                .isEqualByComparingTo(expectedOutstanding);
    }

    private void assertBadRequestCreate(long companyId, MockHttpSession session, String token,
            String json) throws Exception {
        mockMvc.perform(createInvoice(companyId, session, token, json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    /** Creates a DRAFT through the API and returns its id. */
    private long createDraft(long companyId, MockHttpSession session, String token,
            long customerId, String itemsJson) throws Exception {
        return createDraft(companyId, session, token, customerId, null, itemsJson);
    }

    /**
     * Creates a DRAFT with an optional invoice-level description (the list
     * search `q` matches the INVOICE's number/description columns, not the
     * items' descriptions).
     */
    private long createDraft(long companyId, MockHttpSession session, String token,
            long customerId, String description, String itemsJson) throws Exception {
        String body = "{\"customerId\":" + customerId
                + ",\"issueDate\":\"2026-03-01\",\"dueDate\":\"2026-03-31\""
                + (description == null ? "" : ",\"description\":\"" + description + "\"")
                + ",\"items\":" + itemsJson + "}";
        String response = mockMvc.perform(createInvoice(companyId, session, token, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return decimal(response, "$.id").longValue();
    }

    /** Creates and immediately issues a DRAFT; returns the invoice id. */
    private long createIssued(long companyId, MockHttpSession session, String token,
            long customerId, String issueDate, String dueDate, String itemsJson)
            throws Exception {
        String body = "{\"customerId\":" + customerId + ",\"issueDate\":\"" + issueDate
                + "\",\"dueDate\":\"" + dueDate + "\",\"items\":" + itemsJson + "}";
        long invoiceId = decimal(mockMvc.perform(createInvoice(companyId, session, token, body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id").longValue();
        mockMvc.perform(issueInvoice(companyId, invoiceId, session, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        return invoiceId;
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

    /** Direct-SQL fixture: a VERIFIED payment (payments phase not built yet). */
    private long seedVerifiedPayment(String companyCode, long customerId, String number,
            BigDecimal amount, long recordedBy) {
        jdbc.update("""
                INSERT INTO payments (company_id, customer_id, payment_number, amount,
                                      method, received_at, recorded_by, status)
                SELECT id, ?, ?, ?, 'CASH', now(), ?, 'VERIFIED' FROM companies WHERE code = ?
                """, customerId, number, amount, recordedBy, companyCode);
        return jdbc.queryForObject("SELECT id FROM payments WHERE payment_number = ?",
                Long.class, number);
    }

    private long seedAllocation(String companyCode, long customerId, long paymentId,
            long invoiceId, BigDecimal amount, long createdBy) {
        jdbc.update("""
                INSERT INTO payment_allocations (company_id, customer_id, payment_id,
                                                 invoice_id, amount, status, created_by)
                SELECT id, ?, ?, ?, ?, 'ACTIVE', ? FROM companies WHERE code = ?
                """, customerId, paymentId, invoiceId, amount, createdBy, companyCode);
        return jdbc.queryForObject(
                "SELECT id FROM payment_allocations WHERE invoice_id = ? AND amount = ?",
                Long.class, invoiceId, amount);
    }

    private void reverseAllocation(long allocationId, long reversedBy) {
        jdbc.update("""
                UPDATE payment_allocations
                SET status = 'REVERSED', reversed_at = now(), reversed_by = ?,
                    reversal_reason = 'test reversal'
                WHERE id = ?
                """, reversedBy, allocationId);
    }

    private long nextSequence(String companyCode) {
        return jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'INVOICE' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = ?)",
                Long.class, companyCode);
    }

    /**
     * Response money/id fields are parsed via BigDecimal (exact contract).
     * The explicit {@code Object} local is required: without it, generic
     * inference on {@code String.valueOf(JsonPath.read(...))} resolves to
     * the {@code char[]} overload and blows up at runtime.
     */
    private static BigDecimal decimal(String responseBody, String path) {
        Object value = JsonPath.read(responseBody, path);
        return new BigDecimal(String.valueOf(value));
    }

    private MockHttpServletRequestBuilder listInvoices(long companyId, MockHttpSession session) {
        MockHttpServletRequestBuilder request = get(
                "/api/v1/companies/" + companyId + "/invoices");
        if (session != null) {
            request.session(session);
        }
        return request;
    }

    private MockHttpServletRequestBuilder getInvoice(long companyId, long invoiceId,
            MockHttpSession session) {
        return get("/api/v1/companies/" + companyId + "/invoices/" + invoiceId)
                .session(session);
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

    private MockHttpServletRequestBuilder patchInvoice(long companyId, long invoiceId,
            MockHttpSession session, String csrfToken, String json) {
        MockHttpServletRequestBuilder request = patch(
                "/api/v1/companies/" + companyId + "/invoices/" + invoiceId)
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
