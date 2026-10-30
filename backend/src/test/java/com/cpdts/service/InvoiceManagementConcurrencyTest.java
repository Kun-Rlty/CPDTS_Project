package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
import com.cpdts.web.ConflictException;
import com.cpdts.web.InvoiceManagementController.CreateInvoiceRequest;
import com.cpdts.web.InvoiceManagementController.InvoiceDetailDto;
import com.cpdts.web.InvoiceManagementController.InvoiceItemRequest;

/**
 * Concurrency behaviour of invoice mutations against the real database:
 *
 * <ul>
 *   <li>Parallel CREATIONS must produce DISTINCT, CONTIGUOUS invoice
 *       numbers: the number_sequences counter row is locked inside each
 *       creation transaction, so parallel creations serialize on it instead
 *       of colliding (V2 uq_invoices_company_number as the backstop).</li>
 *   <li>Parallel ISSUES of the SAME draft must elect EXACTLY ONE winner:
 *       every issue locks the invoice row {@code FOR UPDATE} and re-checks
 *       the lifecycle status after acquiring the lock, so the losers see
 *       ISSUED and get 409 - never a double-issue, never a torn state.</li>
 * </ul>
 *
 * <p>Calls go through the real transactional service from parallel threads,
 * each with its own security context (like separate HTTP requests).
 */
class InvoiceManagementConcurrencyTest extends CpdtsIntegrationTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 3;
    private static final String ACTOR_EMAIL = "officer.jmx.concurrency@example.test";

    @Autowired
    private InvoiceManagementService invoiceManagementService;

    private long actorId;

    @BeforeEach
    void arrange() {
        clearTestCustomers();
        Long existing = jdbc.query("SELECT id FROM users WHERE email = ?",
                (rs, rowNum) -> rs.getLong(1), ACTOR_EMAIL).stream().findFirst().orElse(null);
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
    }

    @AfterEach
    void cleanUp() {
        deleteAllTestUsers();
    }

    @Test
    void parallelCreationsReceiveDistinctContiguousInvoiceNumbers() throws Exception {
        long sequenceBefore = jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'INVOICE' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                Long.class);
        long companyId = companyId("JMX");
        long customer = newCustomer("Concurrent Co");
        int total = THREADS * PER_THREAD;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<InvoiceDetailDto>> jobs = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                int n = i;
                jobs.add(() -> {
                    // Each "request" authenticates itself, like a real one.
                    SecurityContextHolder.getContext().setAuthentication(
                            CpdtsAuthenticationToken.authenticated(new CpdtsPrincipal(
                                    actorId, ACTOR_EMAIL, "Concurrency Officer", false)));
                    try {
                        return invoiceManagementService.createInvoice(companyId,
                                new CreateInvoiceRequest(customer, "2026-06-01", "2026-06-30",
                                        "Concurrent invoice " + n,
                                        List.of(new InvoiceItemRequest("Work " + n,
                                                BigDecimal.ONE, new BigDecimal("10.00")))));
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            List<InvoiceDetailDto> created = new ArrayList<>();
            for (Future<InvoiceDetailDto> future : pool.invokeAll(jobs)) {
                created.add(future.get(60, TimeUnit.SECONDS));
            }

            // Every creation succeeded with a distinct, correctly formatted number:
            List<String> numbers = created.stream().map(InvoiceDetailDto::invoiceNumber)
                    .sorted().toList();
            assertThat(numbers).hasSize(total).doesNotHaveDuplicates()
                    .allMatch(number -> number.matches("JMX-INV-\\d{5}"));
            List<String> expected = new ArrayList<>();
            for (long value = sequenceBefore; value < sequenceBefore + total; value++) {
                expected.add("JMX-INV-%05d".formatted(value));
            }
            assertThat(numbers)
                    .as("numbers must be exactly the contiguous allocated range")
                    .isEqualTo(expected);

            // The counter advanced by exactly the number of creations:
            assertThat(jdbc.queryForObject(
                    "SELECT next_value FROM number_sequences WHERE kind = 'INVOICE' "
                            + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                    Long.class)).isEqualTo(sequenceBefore + total);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM invoices WHERE company_id = ?", Long.class,
                    companyId)).isEqualTo(total);
            // Each invoice stored its own server-comculated total:
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM invoices WHERE company_id = ? AND total_amount = 10.00",
                    Long.class, companyId)).isEqualTo(total);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIssueAttemptsElectExactlyOneWinner() throws Exception {
        long companyId = companyId("JMX");
        long customer = newCustomer("Issue Race Co");
        // Setup runs on the TEST thread, which needs its own security
        // context (worker threads set theirs individually below).
        SecurityContextHolder.getContext().setAuthentication(
                CpdtsAuthenticationToken.authenticated(new CpdtsPrincipal(
                        actorId, ACTOR_EMAIL, "Concurrency Officer", false)));
        InvoiceDetailDto draft;
        try {
            draft = invoiceManagementService.createInvoice(companyId,
                    new CreateInvoiceRequest(customer, "2026-06-01", "2026-06-30", "Issue race",
                            List.of(new InvoiceItemRequest("Work", BigDecimal.ONE,
                                    new BigDecimal("400.00")))));
        } finally {
            SecurityContextHolder.clearContext();
        }
        long invoiceId = draft.id();
        // Run-relative baseline: audit rows survive runs (append-only).
        long issueAuditBefore = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'INVOICE_ISSUED' "
                        + "AND entity_id = ?", Long.class, invoiceId);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<InvoiceDetailDto>> jobs = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                jobs.add(() -> {
                    SecurityContextHolder.getContext().setAuthentication(
                            CpdtsAuthenticationToken.authenticated(new CpdtsPrincipal(
                                    actorId, ACTOR_EMAIL, "Concurrency Officer", false)));
                    try {
                        return invoiceManagementService.issueInvoice(companyId, invoiceId);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
            }

            int winners = 0;
            int conflicts = 0;
            for (Future<InvoiceDetailDto> future : pool.invokeAll(jobs)) {
                try {
                    InvoiceDetailDto result = future.get(60, TimeUnit.SECONDS);
                    winners++;
                    assertThat(result.status()).isEqualTo("ISSUED");
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof ConflictException) {
                        conflicts++;
                    } else {
                        throw ex;
                    }
                }
            }

            assertThat(winners)
                    .as("exactly one issue may win the race").isEqualTo(1);
            assertThat(conflicts)
                    .as("every loser must be told the invoice is no longer DRAFT")
                    .isEqualTo(THREADS - 1);

            // The stored state agrees: issued once, audited once:
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM invoices WHERE id = ?", String.class, invoiceId))
                    .isEqualTo("ISSUED");
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM audit_logs WHERE action = 'INVOICE_ISSUED' "
                            + "AND entity_id = ?", Long.class, invoiceId))
                    .isEqualTo(issueAuditBefore + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

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
}
