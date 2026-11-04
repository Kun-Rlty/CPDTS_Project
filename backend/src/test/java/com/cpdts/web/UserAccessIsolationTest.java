package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.cpdts.support.CpdtsIntegrationTest;

/** Non-destructive authorization regression against cpdts_test only. */
class UserAccessIsolationTest extends CpdtsIntegrationTest {
    private long admin;
    private long outsider;

    @AfterEach
    void disableFixtures() {
        if (!"cpdts_test".equals(jdbc.queryForObject("SELECT pg_catalog.current_database()", String.class))) {
            throw new IllegalStateException("Refusing fixture cleanup outside cpdts_test");
        }
        jdbc.update("UPDATE users SET status='DISABLED' WHERE id IN (?,?)", admin, outsider);
    }

    @Test
    void companyAdminCannotAccessAnotherCompanyOrProbeExistingAccounts() throws Exception {
        String adminEmail = uniqueEmail("users-admin"), outsiderEmail = uniqueEmail("users-outside");
        String password = UUID.randomUUID().toString();
        admin = insertUser(adminEmail, password, "ACTIVE", false);
        outsider = insertUser(outsiderEmail, password, "ACTIVE", false);
        addMembership(admin, "JMX", "COMPANY_ADMIN", "ACTIVE");
        addMembership(outsider, "KAM", "VIEWER", "ACTIVE");
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + adminEmail + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mockMvc.perform(get("/api/v1/companies/" + companyId("KAM") + "/users").session(session))
                .andExpect(status().isForbidden());
        String path = "/api/v1/companies/" + companyId("JMX") + "/users";
        String own = duplicateResponse(path, session, adminEmail);
        String foreign = duplicateResponse(path, session, outsiderEmail);
        assertThat(foreign).isEqualTo(own).contains("a user with this email already exists");
    }

    private String duplicateResponse(String path, MockHttpSession session, String email) throws Exception {
        return mockMvc.perform(post(path).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
    }
}
