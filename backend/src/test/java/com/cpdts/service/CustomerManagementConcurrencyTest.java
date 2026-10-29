package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import com.cpdts.web.CustomerManagementController.CreateCustomerRequest;
import com.cpdts.web.CustomerManagementController.CustomerDto;

/**
 * Concurrent customer creation must produce DISTINCT, CONTIGUOUS customer
 * codes: the number_sequences counter row is locked inside each creation
 * transaction, so parallel creations serialize on it instead of colliding
 * (V3 number_sequences + V2 uq_customers_company_code as the backstop).
 *
 * <p>Calls go through the real transactional service from parallel threads,
 * each with its own security context (like separate HTTP requests).
 */
class CustomerManagementConcurrencyTest extends CpdtsIntegrationTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 3;
    private static final String ACTOR_EMAIL = "officer.jmx.concurrency@example.test";

    @Autowired
    private CustomerManagementService customerManagementService;

    @BeforeEach
    void arrange() {
        clearTestCustomers();
        Long existing = jdbc.query("SELECT id FROM users WHERE email = ?",
                (rs, rowNum) -> rs.getLong(1), ACTOR_EMAIL).stream().findFirst().orElse(null);
        long actorId;
        if (existing == null) {
            actorId = insertUser(ACTOR_EMAIL, "concurrency officer pw 1", "ACTIVE", false);
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
        this.actorId = actorId;
    }

    @AfterEach
    void cleanUp() {
        deleteAllTestUsers();
    }

    private long actorId;

    @Test
    void parallelCreationsReceiveDistinctContiguousCodes() throws Exception {
        long sequenceBefore = jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'CUSTOMER' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                Long.class);
        long companyId = companyId("JMX");
        int total = THREADS * PER_THREAD;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<CustomerDto>> jobs = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                int n = i;
                jobs.add(() -> {
                    // Each "request" authenticates itself, like a real one.
                    SecurityContextHolder.getContext().setAuthentication(
                            CpdtsAuthenticationToken.authenticated(new CpdtsPrincipal(
                                    actorId, ACTOR_EMAIL, "Concurrency Officer", false)));
                    try {
                        return customerManagementService.createCustomer(companyId,
                                new CreateCustomerRequest("Concurrent Co " + n, null, null, null,
                                        null));
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            List<CustomerDto> created = new ArrayList<>();
            for (Future<CustomerDto> future : pool.invokeAll(jobs)) {
                created.add(future.get(60, TimeUnit.SECONDS));
            }

            // Every creation succeeded with a distinct, correctly formatted code:
            List<String> codes = created.stream().map(CustomerDto::customerCode).sorted().toList();
            assertThat(codes).hasSize(total).doesNotHaveDuplicates()
                    .allMatch(code -> code.matches("JMX-CUST-\\d{5}"));
            List<String> expected = new ArrayList<>();
            for (long value = sequenceBefore; value < sequenceBefore + total; value++) {
                expected.add("JMX-CUST-%05d".formatted(value));
            }
            assertThat(codes).as("codes must be exactly the contiguous allocated range")
                    .isEqualTo(expected);

            // The counter advanced by exactly the number of creations:
            assertThat(jdbc.queryForObject(
                    "SELECT next_value FROM number_sequences WHERE kind = 'CUSTOMER' "
                            + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                    Long.class)).isEqualTo(sequenceBefore + total);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM customers WHERE company_id = ?", Long.class,
                    companyId)).isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }
    }
}
