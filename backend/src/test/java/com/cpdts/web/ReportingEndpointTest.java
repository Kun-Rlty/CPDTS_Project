package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.when;

import com.cpdts.support.CpdtsIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class ReportingEndpointTest extends CpdtsIntegrationTest {
    @MockitoBean Clock clock;
    private long jmx;
    private long kam;
    private long user;
    private MockHttpSession session;

    @BeforeEach
    void setup() throws Exception {
        clearTestCustomers();
        when(clock.getZone()).thenReturn(ZoneId.of("Africa/Dar_es_Salaam"));
        when(clock.instant()).thenReturn(Instant.parse("2026-05-09T21:00:00Z"));
        jmx = companyId("JMX"); kam = companyId("KAM");
        String email = uniqueEmail("report");
        user = insertUser(email, "test password 123", "ACTIVE", false);
        addMembership(user, "JMX", "VIEWER", "ACTIVE");
        session = (MockHttpSession) mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"test password 123\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    @AfterEach
    void cleanup() {
        clearTestCustomers();
        deleteAllTestUsers();
        jdbc.update("DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM roles WHERE code='TEST_REPORT_RESTRICTED')");
        jdbc.update("DELETE FROM roles WHERE code='TEST_REPORT_RESTRICTED'");
    }

    private long customer(long company, String name, String status) {
        return jdbc.queryForObject("INSERT INTO customers(company_id,display_name,status) VALUES (?,?,?) RETURNING id",
                Long.class, company, name, status);
    }
    private long invoice(long customer, String number, String due, String status, int total) {
        return jdbc.queryForObject("""
                INSERT INTO invoices(company_id,customer_id,invoice_number,issue_date,due_date,status,total_amount,voided_at,void_reason)
                VALUES (?,?,?,DATE '2026-01-01',?::date,?,?,CASE WHEN ? = 'VOIDED' THEN now() END,
                        CASE WHEN ? = 'VOIDED' THEN 'test' END) RETURNING id
                """, Long.class, jmx, customer, number, due, status, total, status, status);
    }
    private long payment(long customer, String number, int amount, String status, String received) {
        return jdbc.queryForObject("""
                INSERT INTO payments(company_id,customer_id,payment_number,amount,method,received_at,status,recorded_by)
                VALUES (?,?,?,?,'CASH',?::timestamptz,?,?) RETURNING id
                """, Long.class, jmx, customer, number, amount, received, status, user);
    }
    private long allocate(long customer, long payment, long invoice, int amount) {
        return jdbc.queryForObject("""
                INSERT INTO payment_allocations(company_id,customer_id,payment_id,invoice_id,amount,status,created_by)
                VALUES (?,?,?,?,?,'ACTIVE',?) RETURNING id
                """, Long.class, jmx, customer, payment, invoice, amount, user);
    }
    private String base() { return "/api/v1/companies/" + jmx; }
    private static BigDecimal money(String json, String path) {
        Object v = JsonPath.read(json, path);
        return new BigDecimal(String.valueOf(v));
    }

    @Test
    void totalsExcludeIneligibleRecordsAndAvoidFanout() throws Exception {
        long c = customer(jmx, "Inactive debtor", "INACTIVE");
        long overdue = invoice(c, "I1", "2026-05-09", "ISSUED", 100);
        long today = invoice(c, "I2", "2026-05-10", "ISSUED", 200);
        invoice(c, "I3", "2026-05-09", "DRAFT", 900);
        invoice(c, "I4", "2026-05-09", "VOIDED", 800);
        long p1 = payment(c, "P1", 100, "VERIFIED", "2026-05-09T21:00:00Z");
        long p2 = payment(c, "P2", 100, "VERIFIED", "2026-05-10T20:59:59Z");
        payment(c, "P3", 50, "PENDING_VERIFICATION", "2026-05-10T21:00:00Z");
        payment(c, "P4", 60, "REJECTED", "2026-05-10T21:00:00Z");
        jdbc.update("""
                INSERT INTO payments(company_id,customer_id,payment_number,amount,method,received_at,status,
                                     recorded_by,reversed_at,reversed_by,reversal_reason)
                VALUES (?,?,'P5',900,'CASH',now(),'REVERSED',?,now(),?,'test')
                """, jmx, c, user, user);
        allocate(c, p1, overdue, 30);
        allocate(c, p2, overdue, 20);
        allocate(c, p2, today, 40);
        long reversed = allocate(c, p1, today, 10);
        jdbc.update("UPDATE payment_allocations SET status='REVERSED', reversed_at=now(), reversed_by=?, reversal_reason='test' WHERE id=?", user, reversed);
        String json = mockMvc.perform(get(base() + "/dashboard").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.basis.businessDate").value("2026-05-10"))
                .andExpect(jsonPath("$.pendingPaymentCount").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(money(json, "$.outstandingAmount")).isEqualByComparingTo("210");
        assertThat(money(json, "$.overdueAmount")).isEqualByComparingTo("50");
        assertThat(money(json, "$.pendingPaymentAmount")).isEqualByComparingTo("50");
        assertThat(money(json, "$.verifiedPaymentAmount")).isEqualByComparingTo("200");
        assertThat(money(json, "$.unallocatedCustomerCredit")).isEqualByComparingTo("110");
        String debt = mockMvc.perform(get(base() + "/reports/debt/customers").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].customerStatus").value("INACTIVE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(money(debt, "$.items[0].outstandingAmount")).isEqualByComparingTo("210");
        assertThat(money(debt, "$.items[0].unallocatedCredit")).isEqualByComparingTo("110");
        when(clock.instant()).thenReturn(Instant.parse("2026-05-09T20:59:59Z"));
        mockMvc.perform(get(base() + "/reports/debt/invoices").param("overdueOnly", "true").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        when(clock.instant()).thenReturn(Instant.parse("2026-05-09T21:00:00Z"));
        mockMvc.perform(get(base() + "/reports/debt/invoices").param("overdueOnly", "true").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get(base() + "/invoices/" + overdue).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.derivedStatus").value("OVERDUE"));
        mockMvc.perform(get(base() + "/reports/payments").param("from", "2026-05-10")
                .param("to", "2026-05-10").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(base() + "/reports/payments").param("status", "verified")
                .param("method", "cash").param("customerId", String.valueOf(c))
                .param("size", "1").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get(base() + "/reports/debt/invoices").param("customerId", String.valueOf(c))
                .param("q", "I2").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        when(clock.instant()).thenReturn(Instant.parse("2026-05-10T21:00:00Z"));
        mockMvc.perform(get(base() + "/reports/debt/invoices").param("overdueOnly", "true").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void scopesAndValidatesReportRequests() throws Exception {
        mockMvc.perform(get(base() + "/dashboard")).andExpect(status().isUnauthorized());
        for (String suffix : new String[] { "/dashboard", "/reports/debt/customers", "/reports/debt/invoices", "/reports/payments" }) {
            mockMvc.perform(get("/api/v1/companies/" + kam + suffix).session(session)).andExpect(status().isForbidden());
        }
        mockMvc.perform(get(base() + "/reports/payments").param("size", "101").session(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(base() + "/reports/payments").param("method", "BAD").session(session)).andExpect(status().isBadRequest());
        mockMvc.perform(get(base() + "/reports/payments").param("from", "2026-06-01").param("to", "2026-05-01").session(session)).andExpect(status().isBadRequest());
    }

    @Test
    void recordViewAloneCannotReadReports() throws Exception {
        jdbc.update("INSERT INTO roles(code,name) VALUES ('TEST_REPORT_RESTRICTED','Test report restricted')");
        jdbc.update("""
                INSERT INTO role_permissions(role_id,permission_id)
                SELECT r.id,p.id FROM roles r,permissions p
                WHERE r.code='TEST_REPORT_RESTRICTED' AND p.code='RECORD_VIEW'
                """);
        jdbc.update("UPDATE company_memberships SET role_id=(SELECT id FROM roles WHERE code='TEST_REPORT_RESTRICTED') WHERE user_id=?", user);
        mockMvc.perform(get(base() + "/invoices").session(session)).andExpect(status().isOk());
        for (String suffix : new String[] { "/dashboard", "/reports/debt/customers", "/reports/debt/invoices", "/reports/payments" }) {
            mockMvc.perform(get(base() + suffix).session(session)).andExpect(status().isForbidden());
        }
    }

    @Test
    void platformAdministratorSeesCompaniesSeparately() throws Exception {
        long c = customer(kam, "KAM debtor", "ACTIVE");
        jdbc.update("""
                INSERT INTO invoices(company_id,customer_id,invoice_number,issue_date,due_date,status,total_amount)
                VALUES (?,?,'KAM-I',DATE '2026-01-01',DATE '2026-05-09','ISSUED',123)
                """, kam, c);
        jdbc.update("UPDATE users SET is_platform_admin=true WHERE id=?", user);
        String jmxDashboard = mockMvc.perform(get(base() + "/dashboard").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String kamDashboard = mockMvc.perform(get("/api/v1/companies/" + kam + "/dashboard").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(money(jmxDashboard, "$.outstandingAmount")).isEqualByComparingTo("0");
        assertThat(money(kamDashboard, "$.outstandingAmount")).isEqualByComparingTo("123");
    }
}
