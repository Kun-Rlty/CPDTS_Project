package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cpdts.security.CpdtsAuthenticationToken;
import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.support.CpdtsIntegrationTest;
import com.cpdts.web.CustomerManagementController.CreateCustomerRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional atomicity of customer creation: the audit event is written in
 * the SAME transaction as the sequence increment and the customer insert, so
 * a failure while writing the audit row must roll back ALL of them (PRD
 * FR-09; AGENTS.md rule 9/10).
 *
 * <p>The fault is injected by replacing the JSON mapper bean (used only for
 * audit JSON serialization) with a mock that throws. The mock must be of type
 * {@link JsonMapper} because that is the concrete type of Boot's mapper bean.
 */
class CustomerManagementTransactionTest extends CpdtsIntegrationTest {

    @MockitoBean
    private JsonMapper objectMapper;

    @Autowired
    private CustomerManagementService customerManagementService;

    private final String actorEmail = "officer.jmx.txtest@example.test";

    @BeforeEach
    void arrange() {
        clearTestCustomers();
        Long existing = jdbc.query("SELECT id FROM users WHERE email = ?",
                (rs, rowNum) -> rs.getLong(1), actorEmail).stream().findFirst().orElse(null);
        long actorId;
        if (existing == null) {
            actorId = insertUser(actorEmail, "tx officer password 1", "ACTIVE", false);
        } else {
            jdbc.update("UPDATE users SET status = 'ACTIVE', is_platform_admin = FALSE, "
                    + "updated_at = now() WHERE id = ?", existing);
            actorId = existing;
        }
        if (jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Integer.class, actorId, companyId("JMX")) == 0) {
            addMembership(actorId, "JMX", "FINANCE_OFFICER", "ACTIVE");
        }
        SecurityContextHolder.getContext().setAuthentication(
                CpdtsAuthenticationToken.authenticated(
                        new CpdtsPrincipal(actorId, actorEmail, "Tx Officer", false)));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        deleteAllTestUsers();
    }

    @Test
    void auditFailureRollsBackTheCustomerInsertAndTheSequenceIncrement() {
        long sequenceBefore = jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'CUSTOMER' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                Long.class);

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("simulated audit failure"));

        String name = "Rollback Victim Ltd";
        assertThatThrownBy(() -> customerManagementService.createCustomer(companyId("JMX"),
                new CreateCustomerRequest(name, null, null, null, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit failure");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE display_name = ?",
                Long.class, name))
                .as("the customer insert must roll back with the failed audit write").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'CUSTOMER' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                Long.class))
                .as("the sequence increment must roll back too - no number is burned")
                .isEqualTo(sequenceBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE after_data->>'display_name' = ?",
                Long.class, name))
                .as("no partial audit row may exist").isZero();
    }
}
