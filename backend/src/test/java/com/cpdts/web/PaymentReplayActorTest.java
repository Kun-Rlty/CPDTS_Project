package com.cpdts.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.cpdts.support.CpdtsIntegrationTest;

/** Non-destructive test: creates uniquely named records only in cpdts_test. */
class PaymentReplayActorTest extends CpdtsIntegrationTest {
    private long userA;
    private long userB;

    @AfterEach
    void deactivateFixtures() {
        if (!"cpdts_test".equals(jdbc.queryForObject("SELECT pg_catalog.current_database()", String.class))) {
            throw new IllegalStateException("Refusing fixture cleanup outside cpdts_test");
        }
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id IN (?, ?)", userA, userB);
    }

    @Test
    void anotherActorCannotReplayTheFirstActorsPaymentKey() throws Exception {
        String a = uniqueEmail("browser-replay-a"), b = uniqueEmail("browser-replay-b");
        String password = UUID.randomUUID().toString();
        userA = insertUser(a, password, "ACTIVE", false);
        userB = insertUser(b, password, "ACTIVE", false);
        addMembership(userA, "JMX", "FINANCE_OFFICER", "ACTIVE");
        addMembership(userB, "JMX", "FINANCE_OFFICER", "ACTIVE");
        long company = companyId("JMX");
        long customer = jdbc.queryForObject("INSERT INTO customers(company_id,display_name) VALUES (?,?) RETURNING id",
                Long.class, company, "Browser replay " + UUID.randomUUID());
        String body = "{\"customerId\":" + customer + ",\"amount\":10.00,\"method\":\"CASH\",\"receivedAt\":\"2026-05-10\"}";
        String key = UUID.randomUUID().toString();
        MockHttpSession sessionA = login(a, password), sessionB = login(b, password);
        String url = "/api/v1/companies/" + company + "/payments";
        mockMvc.perform(post(url).session(sessionA).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mockMvc.perform(post(url).session(sessionA).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post(url).session(sessionB).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    private MockHttpSession login(String email, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
}
