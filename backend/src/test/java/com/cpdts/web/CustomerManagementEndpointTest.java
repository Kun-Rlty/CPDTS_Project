package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cpdts.support.CpdtsIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * API-level tests for company-scoped customer management (PRD FR-03):
 *
 * <ul>
 *   <li>permissions: RECORD_VIEW for reads, CUSTOMER_MANAGE for mutations,
 *       platform admin in both companies, company admin only their own;</li>
 *   <li>company isolation: other companies' customers are 404 through my
 *       path (no disclosure), cross-company writes are 403;</li>
 *   <li>server-side code generation from number_sequences (client code never
 *       trusted) and validation against the V2 schema bounds;</li>
 *   <li>bounded pagination + escaped search, scoped to the authorized
 *       company;</li>
 *   <li>no hard delete (no DELETE route -&gt; 405), activation via PATCH;</li>
 *   <li>CSRF, unauthenticated access, atomic audit events with the session
 *       actor.</li>
 * </ul>
 */
class CustomerManagementEndpointTest extends CpdtsIntegrationTest {

    private static final String ACTOR_PASSWORD = "fixture actor password 1";

    // Fixed fixture emails on purpose: customer mutations are audited and the
    // audit actor can never be deleted (append-only audit_logs FK), so a
    // bounded set of reusable actors keeps the test database bounded.
    private static final String PLATFORM_ACTOR_EMAIL = "platform.actor@example.test";
    private static final String COADMIN_JMX_EMAIL = "coadmin.jmx.actor@example.test";
    private static final String COADMIN_KAM_EMAIL = "coadmin.kam.actor@example.test";
    private static final String VIEWER_JMX_EMAIL = "viewer.jmx.customer@example.test";
    private static final String OFFICER_JMX_EMAIL = "officer.jmx.customer@example.test";

    @BeforeEach
    void startWithNoCustomers() {
        // Deterministic list/pagination/sequence assertions: TRUNCATE is the
        // only legal cleanup path around the V2 no-delete trigger (test DB).
        clearTestCustomers();
    }

    @AfterEach
    void cleanUpTestUsers() {
        deleteAllTestUsers();
    }

    // ------------------------------------------------------------------
    // Create: server-generated code, client identifiers ignored, atomic audit
    // ------------------------------------------------------------------

    @Test
    void financeOfficerCreatesCustomerWithServerGeneratedCodeAndAtomicAudit() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Counters are NEVER reset (append-only test DB), so every sequence
        // assertion below is relative to a snapshot taken here:
        long sequenceBefore = nextCustomerSequence("JMX");
        long kamSequenceBefore = nextCustomerSequence("KAM");

        // code / customerCode / companyId / id in the body must be IGNORED:
        String body = """
                {"displayName":"Alpha Traders Ltd","phone":"+255 700 000 001",
                 "email":"alpha@example.test","address":"Dar es Salaam","notes":"walk-in",
                 "code":"FORGED-1","customerCode":"FORGED-2","companyId":999999,"id":42}""";
        MvcResult created = mockMvc.perform(createCustomer(companyId("JMX"), session, token, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Alpha Traders Ltd"))
                .andExpect(jsonPath("$.phone").value("+255 700 000 001"))
                .andExpect(jsonPath("$.email").value("alpha@example.test"))
                .andExpect(jsonPath("$.address").value("Dar es Salaam"))
                .andExpect(jsonPath("$.notes").value("walk-in"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.customerCode").value("JMX-CUST-" + "%05d".formatted(
                        sequenceBefore)))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn();
        long customerId = customerIdFrom(created.getResponse().getContentAsString());
        assertThat(customerId).isPositive();

        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT company_id, customer_code, created_by, status FROM customers WHERE id = ?",
                customerId);
        assertThat(((Number) row.get("company_id")).longValue()).isEqualTo(companyId("JMX"));
        assertThat(row.get("customer_code")).isEqualTo("JMX-CUST-%05d".formatted(sequenceBefore));
        assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(officer);
        assertThat(row.get("status")).isEqualTo("ACTIVE");

        // Sequence advanced exactly once and stays company-scoped:
        assertThat(nextCustomerSequence("JMX")).isEqualTo(sequenceBefore + 1);
        assertThat(nextCustomerSequence("KAM")).isEqualTo(kamSequenceBefore)
                .as("the KAM counter must be untouched by a JMX creation");

        // Audit event written atomically with the change, actor = session user.
        // audit_logs is append-only and customer ids restart per test after
        // TRUNCATE, so older runs share entity ids: take the NEWEST row.
        java.util.Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, company_id, entity_type, entity_id, "
                        + "after_data->>'customer_code' AS code, "
                        + "after_data->>'display_name' AS name, "
                        + "after_data->>'status' AS status, after_data::text AS data "
                        + "FROM audit_logs WHERE action = 'CUSTOMER_CREATED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                customerId);
        assertThat(audit.get("actor_user_id")).isEqualTo(officer);
        assertThat(audit.get("company_id")).isEqualTo(companyId("JMX"));
        assertThat(audit.get("entity_type")).isEqualTo("CUSTOMER");
        assertThat(audit.get("code")).isEqualTo(row.get("customer_code"));
        assertThat(audit.get("name")).isEqualTo("Alpha Traders Ltd");
        assertThat(audit.get("status")).isEqualTo("ACTIVE");
        assertThat((String) audit.get("data"))
                .as("audit data is minimized - never notes or contact details")
                .doesNotContain("walk-in").doesNotContain("alpha@example.test");

        // A second creation gets the next contiguous code:
        mockMvc.perform(createCustomer(companyId("JMX"), session, token,
                        "{\"displayName\":\"Beta Traders Ltd\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerCode")
                        .value("JMX-CUST-%05d".formatted(sequenceBefore + 1)));
    }

    @Test
    void customerCodesNeverCollideAcrossCompanies() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Counters are never reset, so read them first and assert each
        // company's code derives from ITS OWN counter (relative check).
        long jmxBefore = nextCustomerSequence("JMX");
        long kamBefore = nextCustomerSequence("KAM");
        MvcResult jmx = mockMvc.perform(createCustomer(companyId("JMX"), session, token,
                        "{\"displayName\":\"Shared Name Ltd\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerCode")
                        .value("JMX-CUST-%05d".formatted(jmxBefore)))
                .andReturn();
        MvcResult kam = mockMvc.perform(createCustomer(companyId("KAM"), session, token,
                        "{\"displayName\":\"Shared Name Ltd\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerCode")
                        .value("KAM-CUST-%05d".formatted(kamBefore)))
                .andReturn();
        assertThat(customerIdFrom(jmx.getResponse().getContentAsString())).isNotEqualTo(
                customerIdFrom(kam.getResponse().getContentAsString()));
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    @Test
    void viewerCanReadButNotMutate() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession officerSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        long customerId = customerIdFrom(mockMvc
                .perform(createCustomer(companyId("JMX"), officerSession,
                        csrfToken(officerSession), "{\"displayName\":\"Read Only Ltd\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(officer).isPositive();

        long viewer = roleMember(VIEWER_JMX_EMAIL, "VIEWER", "JMX");
        MockHttpSession viewerSession = login(VIEWER_JMX_EMAIL, ACTOR_PASSWORD);
        String viewerToken = csrfToken(viewerSession);

        // RECORD_VIEW: reads work...
        mockMvc.perform(listCustomers(companyId("JMX"), viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(getCustomer(companyId("JMX"), customerId, viewerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Read Only Ltd"));

        // ...but CUSTOMER_MANAGE is missing: mutations are refused.
        mockMvc.perform(createCustomer(companyId("JMX"), viewerSession, viewerToken,
                        "{\"displayName\":\"Sneaky Ltd\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(patchCustomer(companyId("JMX"), customerId, viewerSession, viewerToken,
                        "{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(jdbc.queryForObject("SELECT status FROM customers WHERE id = ?",
                String.class, customerId)).isEqualTo("ACTIVE");
    }

    @Test
    void companyAdminCannotManageCustomersInTheOtherCompany() throws Exception {
        roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        mockMvc.perform(listCustomers(companyId("KAM"), session))
                .andExpect(status().isForbidden());
        mockMvc.perform(createCustomer(companyId("KAM"), session, token,
                        "{\"displayName\":\"Cross Border Ltd\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE company_id = ?",
                Long.class, companyId("KAM"))).isZero()
                .as("nothing may be created in the other company");
    }

    // ------------------------------------------------------------------
    // Company isolation: 404, never a cross-company disclosure
    // ------------------------------------------------------------------

    @Test
    void customerOfAnotherCompanyIsNotFoundThroughMyPath() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession platformSession = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        long kamCustomerId = customerIdFrom(mockMvc
                .perform(createCustomer(companyId("KAM"), platformSession,
                        csrfToken(platformSession), "{\"displayName\":\"KAM Only Ltd\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        roleMember(COADMIN_JMX_EMAIL, "COMPANY_ADMIN", "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // The SAME id through the JMX path is indistinguishable from a
        // nonexistent customer: 404, no data, no "wrong company" hint.
        mockMvc.perform(getCustomer(companyId("JMX"), kamCustomerId, session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(patchCustomer(companyId("JMX"), kamCustomerId, session, token,
                        "{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        // And it never appeared in my list either:
        mockMvc.perform(listCustomers(companyId("JMX"), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // The KAM row is untouched:
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT company_id, display_name, status FROM customers WHERE id = ?",
                kamCustomerId);
        assertThat(((Number) row.get("company_id")).longValue()).isEqualTo(companyId("KAM"));
        assertThat(row.get("display_name")).isEqualTo("KAM Only Ltd");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
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

        // Missing / blank display name:
        mockMvc.perform(createCustomer(jmx, session, token, "{\"displayName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("display name is required"));
        mockMvc.perform(createCustomer(jmx, session, token, "{}"))
                .andExpect(status().isBadRequest());

        // Schema bounds: display_name 255, phone 50, email 320, address 500:
        assertBadRequestCreate(jmx, session, token,
                "{\"displayName\":\"" + "x".repeat(256) + "\"}");
        assertBadRequestCreate(jmx, session, token,
                "{\"displayName\":\"Bound Ltd\",\"phone\":\"" + "1".repeat(51) + "\"}");
        assertBadRequestCreate(jmx, session, token,
                "{\"displayName\":\"Bound Ltd\",\"email\":\"not-an-email\"}");
        assertBadRequestCreate(jmx, session, token,
                "{\"displayName\":\"Bound Ltd\",\"email\":\"" + "a".repeat(318) + "@x.t\"}");
        assertBadRequestCreate(jmx, session, token,
                "{\"displayName\":\"Bound Ltd\",\"address\":\"" + "a".repeat(501) + "\"}");

        // Engineering bound on the TEXT notes column (10,000 chars):
        assertBadRequestCreate(jmx, session, token,
                "{\"displayName\":\"Bound Ltd\",\"notes\":\"" + "n".repeat(10_001) + "\"}");
        // (exactly at the bound is still accepted)
        mockMvc.perform(createCustomer(jmx, session, token,
                        "{\"displayName\":\"Bound Ltd\",\"notes\":\"" + "n".repeat(10_000) + "\"}"))
                .andExpect(status().isCreated());

        // Empty PATCH and unknown status:
        long customerId = customerIdFrom(mockMvc
                .perform(createCustomer(jmx, session, token, "{\"displayName\":\"Patch Ltd\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(patchCustomer(jmx, customerId, session, token, "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patchCustomer(jmx, customerId, session, token,
                        "{\"status\":\"BANANA\"}"))
                .andExpect(status().isBadRequest());

        // Pagination bounds and filters:
        mockMvc.perform(listCustomers(jmx, session).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(listCustomers(jmx, session).param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(listCustomers(jmx, session).param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(listCustomers(jmx, session).param("status", "BANANA"))
                .andExpect(status().isBadRequest());

        // Unknown company fails closed; non-numeric path is a 400:
        mockMvc.perform(listCustomers(999_999_999L, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(get("/api/v1/companies/abc/customers").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        // No rows beyond the accepted ones exist:
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE company_id = ?",
                Long.class, jmx)).isEqualTo(2)
                .as("only the two accepted creations (at-bound notes + Patch Ltd) may exist");
    }

    // ------------------------------------------------------------------
    // Update: only provided fields, audited diff, idempotence, clearing
    // ------------------------------------------------------------------

    @Test
    void updateChangesOnlyProvidedFieldsAndAuditsTheDiff() throws Exception {
        long officer = roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long customerId = customerIdFrom(mockMvc
                .perform(createCustomer(companyId("JMX"), session, token,
                        "{\"displayName\":\"Original Ltd\",\"phone\":\"+255 711 111 111\","
                                + "\"email\":\"original@example.test\",\"notes\":\"keep me\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        // PATCH the display name only:
        mockMvc.perform(patchCustomer(companyId("JMX"), customerId, session, token,
                        "{\"displayName\":\"Renamed Ltd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed Ltd"))
                .andExpect(jsonPath("$.phone").value("+255 711 111 111"))
                .andExpect(jsonPath("$.email").value("original@example.test"))
                .andExpect(jsonPath("$.notes").value("keep me"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Newest audit row = the rename just performed (append-only table
        // retains rows from earlier tests/runs for reused identity ids):
        long auditId = jdbc.queryForObject(
                "SELECT id FROM audit_logs WHERE action = 'CUSTOMER_UPDATED' AND entity_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, customerId);
        java.util.Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, before_data, after_data FROM audit_logs WHERE id = ?",
                auditId);
        assertThat(audit.get("actor_user_id")).isEqualTo(officer);
        // Read the diff as JSON, not as a raw string: PostgreSQL jsonb
        // normalizes formatting (it stores {"k": "v"} with a space), so a
        // literal string match on the serialized form would be brittle.
        String beforeJson = audit.get("before_data").toString();
        String afterJson = audit.get("after_data").toString();
        assertThat(JsonPath.<String>read(beforeJson, "$.display_name")).isEqualTo("Original Ltd");
        assertThat(JsonPath.<String>read(afterJson, "$.display_name")).isEqualTo("Renamed Ltd");
        assertThat(JsonPath.<Integer>read(beforeJson, "$.length()"))
                .as("the diff must contain ONLY the changed field")
                .isEqualTo(1);

        long updatesBeforeRepeat = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CUSTOMER_UPDATED' "
                        + "AND entity_id = ?", Long.class, customerId);
        // An idempotent repeat (same value) is silent - no second audit row:
        mockMvc.perform(patchCustomer(companyId("JMX"), customerId, session, token,
                        "{\"displayName\":\"Renamed Ltd\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CUSTOMER_UPDATED' "
                        + "AND entity_id = ?", Long.class, customerId))
                .isEqualTo(updatesBeforeRepeat)
                .as("an idempotent PATCH must not add an audit event");

        // An empty string CLEARS a nullable field (phone -> NULL):
        mockMvc.perform(patchCustomer(companyId("JMX"), customerId, session, token,
                        "{\"phone\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").isEmpty());
        assertThat(jdbc.queryForObject("SELECT phone FROM customers WHERE id = ?",
                String.class, customerId)).isNull();
    }

    // ------------------------------------------------------------------
    // Deactivate / reactivate - never delete
    // ------------------------------------------------------------------

    @Test
    void deactivationAndReactivationWorkAndThereIsNoDelete() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long customerId = customerIdFrom(mockMvc
                .perform(createCustomer(companyId("JMX"), session, token,
                        "{\"displayName\":\"Seasonal Ltd\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        long statusAuditsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CUSTOMER_UPDATED' "
                        + "AND entity_id = ? AND before_data ?? 'status'", Long.class, customerId);

        // Deactivate:
        mockMvc.perform(patchCustomer(companyId("JMX"), customerId, session, token,
                        "{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(getCustomer(companyId("JMX"), customerId, session))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        // The status filter drives the lists:
        mockMvc.perform(listCustomers(companyId("JMX"), session).param("status", "ACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(listCustomers(companyId("JMX"), session).param("status", "INACTIVE"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(customerId));

        // One audit event per actual status change (delta against the
        // append-only table), with the status field present in the diff:
        // Reactivate:
        mockMvc.perform(patchCustomer(companyId("JMX"), customerId, session, token,
                        "{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'CUSTOMER_UPDATED' "
                        + "AND entity_id = ? AND before_data ?? 'status'", Long.class, customerId))
                .isEqualTo(statusAuditsBefore + 2)
                .as("deactivate + reactivate must add exactly two status diff events");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM customers WHERE id = ?", Long.class, customerId))
                .as("customers are deactivated, never deleted").isEqualTo(1);

        // There is deliberately no DELETE route on known customer paths:
        MvcResult deleteAttempt = mockMvc.perform(delete(
                        "/api/v1/companies/" + companyId("JMX") + "/customers/" + customerId)
                        .session(session).header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"))
                .andReturn();
        assertThat(deleteAttempt.getResponse().getHeader("Allow")).isEqualTo("GET, PATCH");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?",
                Long.class, customerId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Pagination + search, scoped and escaped
    // ------------------------------------------------------------------

    @Test
    void listIsPaginatedEscapedAndCompanyScoped() throws Exception {
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession jmxSession = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        String jmxToken = csrfToken(jmxSession);
        for (String name : new String[] {"Pagetest Alpha", "Pagetest Beta", "Pagetest Gamma",
                "Percent 100% Co", "Under_score Co", "Decoy 1000 Underss Corp"}) {
            mockMvc.perform(createCustomer(companyId("JMX"), jmxSession, jmxToken,
                            "{\"displayName\":\"" + name + "\"}"))
                    .andExpect(status().isCreated());
        }

        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession platformSession = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String platformToken = csrfToken(platformSession);
        long kamBefore = nextCustomerSequence("KAM");
        for (String name : new String[] {"Pagetest KAM One", "Pagetest KAM Two"}) {
            mockMvc.perform(createCustomer(companyId("KAM"), platformSession, platformToken,
                            "{\"displayName\":\"" + name + "\"}"))
                    .andExpect(status().isCreated());
        }

        // Search never crosses the company line:
        String jmxSearch = mockMvc.perform(
                        listCustomers(companyId("JMX"), jmxSession).param("q", "Pagetest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(jmxSearch,
                "$.items[*].customerCode")).allMatch(code -> code.startsWith("JMX-"));
        mockMvc.perform(listCustomers(companyId("KAM"), platformSession).param("q", "Pagetest"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].customerCode")
                        .value("KAM-CUST-%05d".formatted(kamBefore)));

        // Bounded pagination: 3 matches, 2 per page -> 2 pages, split 2/1:
        mockMvc.perform(listCustomers(companyId("JMX"), jmxSession)
                        .param("q", "Pagetest").param("page", "0").param("size", "2"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].displayName").value("Pagetest Alpha"))
                .andExpect(jsonPath("$.items[1].displayName").value("Pagetest Beta"));
        mockMvc.perform(listCustomers(companyId("JMX"), jmxSession)
                        .param("q", "Pagetest").param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Pagetest Gamma"));

        // ILIKE wildcards typed by users match literally (escaped '%'):
        mockMvc.perform(listCustomers(companyId("JMX"), jmxSession).param("q", "100%"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Percent 100% Co"));
        // (escaped '_': the Decoy ...Underss row would match unescaped)
        mockMvc.perform(listCustomers(companyId("JMX"), jmxSession).param("q", "under_s"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("Under_score Co"));
    }

    // ------------------------------------------------------------------
    // Authentication / CSRF
    // ------------------------------------------------------------------

    @Test
    void unauthenticatedAndCsrfRulesApply() throws Exception {
        mockMvc.perform(listCustomers(companyId("JMX"), null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));

        // Unsafe methods without a session are refused by CSRF first:
        mockMvc.perform(createCustomer(companyId("JMX"), null, null,
                        "{\"displayName\":\"Anon Ltd\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        // Authenticated but no CSRF token: still refused.
        roleMember(OFFICER_JMX_EMAIL, "FINANCE_OFFICER", "JMX");
        MockHttpSession session = login(OFFICER_JMX_EMAIL, ACTOR_PASSWORD);
        mockMvc.perform(post("/api/v1/companies/" + companyId("JMX") + "/customers")
                        .session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"No Token Ltd\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class)).isZero();
    }

    @Test
    void platformAdminManagesCustomersInBothCompanies() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        long kamBefore = nextCustomerSequence("KAM");
        mockMvc.perform(createCustomer(companyId("KAM"), session, token,
                        "{\"displayName\":\"KAM Customer Ltd\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerCode")
                        .value("KAM-CUST-%05d".formatted(kamBefore)));
        mockMvc.perform(listCustomers(companyId("JMX"), session))
                .andExpect(status().isOk());
        mockMvc.perform(listCustomers(companyId("KAM"), session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void assertBadRequestCreate(long companyId, MockHttpSession session, String token,
            String json) throws Exception {
        mockMvc.perform(createCustomer(companyId, session, token, json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    private long nextCustomerSequence(String companyCode) {
        return jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'CUSTOMER' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = ?)",
                Long.class, companyCode);
    }

    private static long customerIdFrom(String responseBody) {
        return ((Number) JsonPath.read(responseBody, "$.id")).longValue();
    }

    private MockHttpServletRequestBuilder listCustomers(long companyId, MockHttpSession session) {
        MockHttpServletRequestBuilder request = get(
                "/api/v1/companies/" + companyId + "/customers");
        if (session != null) {
            request.session(session);
        }
        return request;
    }

    private MockHttpServletRequestBuilder getCustomer(long companyId, long customerId,
            MockHttpSession session) {
        return get("/api/v1/companies/" + companyId + "/customers/" + customerId)
                .session(session);
    }

    private MockHttpServletRequestBuilder createCustomer(long companyId, MockHttpSession session,
            String csrfToken, String json) {
        MockHttpServletRequestBuilder request = post(
                "/api/v1/companies/" + companyId + "/customers")
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

    private MockHttpServletRequestBuilder patchCustomer(long companyId, long customerId,
            MockHttpSession session, String csrfToken, String json) {
        MockHttpServletRequestBuilder request = patch(
                "/api/v1/companies/" + companyId + "/customers/" + customerId)
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

    /** Creates or resets a fixed fixture user and grants the given role. */
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
}
