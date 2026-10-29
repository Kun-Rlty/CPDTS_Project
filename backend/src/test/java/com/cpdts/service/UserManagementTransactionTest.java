package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cpdts.security.CpdtsAuthenticationToken;
import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.support.CpdtsIntegrationTest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional atomicity of user/membership management: the audit event is
 * written in the SAME transaction as the change, so a failure while writing
 * the audit row must roll back the user and membership inserts too (PRD
 * FR-09, AGENTS.md rule 9 - no privileged record without its audit trail).
 *
 * <p>The fault is injected by replacing the JSON mapper bean (used only for
 * audit JSON serialization) with a mock that throws. The mock must be of type
 * {@link JsonMapper} because that is the concrete type of Boot's mapper bean.
 */
class UserManagementTransactionTest extends CpdtsIntegrationTest {

    @MockitoBean
    private JsonMapper objectMapper;

    @Autowired
    private UserManagementService userManagementService;

    @AfterEach
    void clearContextAndCleanUp() {
        SecurityContextHolder.clearContext();
        deleteAllTestUsers();
    }

    @Test
    void auditFailureRollsBackTheUserAndMembershipInserts() {
        String actorEmail = uniqueEmail("tx-actor");
        long actorId = insertUser(actorEmail, "tx actor password 1", "ACTIVE", false);
        addMembership(actorId, "JMX", "COMPANY_ADMIN", "ACTIVE");
        SecurityContextHolder.getContext().setAuthentication(
                CpdtsAuthenticationToken.authenticated(
                        new CpdtsPrincipal(actorId, actorEmail, "Tx Actor", false)));

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("simulated audit failure"));

        String email = uniqueEmail("tx-victim");
        assertThatThrownBy(() -> userManagementService.createUser(
                        companyId("JMX"), email, "Tx Victim", "tx victim password 1", "VIEWER"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit failure");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                        Long.class, email))
                .as("the user insert must roll back with the failed audit write")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM company_memberships m "
                                + "JOIN users u ON u.id = m.user_id WHERE u.email = ?",
                        Long.class, email))
                .as("the membership insert must roll back with the failed audit write")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_logs WHERE after_data->>'email' = ?",
                        Long.class, email))
                .as("no partial audit row may exist")
                .isZero();
    }
}
