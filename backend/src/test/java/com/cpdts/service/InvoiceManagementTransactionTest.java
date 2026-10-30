package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cpdts.security.CpdtsAuthenticationToken;
import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.support.CpdtsIntegrationTest;
import com.cpdts.web.InvoiceManagementController.CreateInvoiceRequest;
import com.cpdts.web.InvoiceManagementController.InvoiceItemRequest;
import com.cpdts.web.InvoiceManagementController.VoidInvoiceRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional atomicity of invoice mutations (PRD FR-04/FR-09; AGENTS.md
 * rules 9/10): the audit event is written in the SAME transaction as the
 * change it describes, so a failure while writing the audit row must roll
 * back EVERY part of the mutation:
 *
 * <ul>
 *   <li>create: the invoice insert, ALL line items, AND the
 *       number_sequences increment (no invoice number is ever burned);</li>
 *   <li>void: the status/voided_at/void_reason update that already ran
 *       inside the transaction must revert - the invoice stays ISSUED.</li>
 * </ul>
 *
 * <p>The fault is injected by replacing the JSON mapper bean (used only for
 * audit JSON serialization) with a mock that throws. The mock must be of
 * type {@link JsonMapper} because that is the concrete type of Boot's
 * mapper bean. An UNSTUBBED mock returns null from
 * {@code writeValueAsString}, which the audit insert accepts (nullable
 * jsonb columns) - that is what lets setup mutations succeed before the
 * stubbing flips the mapper into a throwing one.
 */
class InvoiceManagementTransactionTest extends CpdtsIntegrationTest {

    @MockitoBean
    private JsonMapper objectMapper;

    @Autowired
    private InvoiceManagementService invoiceManagementService;

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
    void auditFailureRollsBackTheInvoiceInsertItemsAndSequenceIncrement() {
        long sequenceBefore = nextInvoiceSequence();
        long customer = newCustomer("Rollback Co");
        long invoiceCountBefore = jdbc.queryForObject(
                "SELECT count(*) FROM invoices WHERE company_id = ?", Long.class,
                companyId("JMX"));
        // Run-relative baseline: audit rows are append-only and survive runs.
        long createAuditBefore = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'INVOICE_CREATED'",
                Long.class);

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("simulated audit failure"));

        CreateInvoiceRequest request = new CreateInvoiceRequest(customer, "2026-05-01",
                "2026-05-31", "Rollback victim invoice", List.of(
                        new InvoiceItemRequest("Work A", new BigDecimal("2"),
                                new BigDecimal("123.45")),
                        new InvoiceItemRequest("Work B", new BigDecimal("1"),
                                new BigDecimal("50.00"))));
        assertThatThrownBy(() -> invoiceManagementService.createInvoice(companyId("JMX"),
                request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit failure");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoices WHERE company_id = ? AND customer_id = ?",
                Long.class, companyId("JMX"), customer))
                .as("the invoice insert must roll back with the failed audit write").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoices WHERE company_id = ?", Long.class,
                companyId("JMX")))
                .as("no invoice at all may appear beyond the accepted baseline")
                .isEqualTo(invoiceCountBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoice_items i JOIN invoices v ON v.id = i.invoice_id "
                        + "WHERE v.customer_id = ?", Long.class, customer))
                .as("no orphan line items may survive the rollback").isZero();
        assertThat(nextInvoiceSequence())
                .as("the sequence increment must roll back too - no number is burned")
                .isEqualTo(sequenceBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'INVOICE_CREATED'",
                Long.class))
                .as("no partial audit row may exist for the failed creation")
                .isEqualTo(createAuditBefore);
    }

    @Test
    void auditFailureRollsBackAVoidThatAlreadyUpdatedTheStatus() {
        long customer = newCustomer("Void Rollback Co");
        // Setup mutations run with the UNSTUBBED mock (null JSON accepted):
        long invoiceId = invoiceManagementService.createInvoice(companyId("JMX"),
                new CreateInvoiceRequest(customer, "2026-05-01", "2026-05-31", null,
                        List.of(new InvoiceItemRequest("Work", BigDecimal.ONE,
                                new BigDecimal("75.00"))))).id();
        invoiceManagementService.issueInvoice(companyId("JMX"), invoiceId);
        assertThat(jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?",
                String.class, invoiceId)).isEqualTo("ISSUED");
        long voidAuditBefore = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'INVOICE_VOIDED' "
                        + "AND entity_id = ?", Long.class, invoiceId);

        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new RuntimeException("simulated audit failure"));

        assertThatThrownBy(() -> invoiceManagementService.voidInvoice(companyId("JMX"),
                invoiceId, new VoidInvoiceRequest("should never persist")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated audit failure");

        // The UPDATE ran BEFORE the audit insert inside the same
        // transaction - the rollback must revert it completely:
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, voided_at, void_reason FROM invoices WHERE id = ?",
                invoiceId);
        assertThat(row.get("status"))
                .as("the void must roll back - the invoice stays ISSUED").isEqualTo("ISSUED");
        assertThat(row.get("voided_at")).isNull();
        assertThat(row.get("void_reason")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'INVOICE_VOIDED' "
                        + "AND entity_id = ?", Long.class, invoiceId))
                .isEqualTo(voidAuditBefore);
    }

    // ------------------------------------------------------------------
    // Fixtures/helpers
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

    private long nextInvoiceSequence() {
        return jdbc.queryForObject(
                "SELECT next_value FROM number_sequences WHERE kind = 'INVOICE' "
                        + "AND company_id = (SELECT id FROM companies WHERE code = 'JMX')",
                Long.class);
    }
}
