package com.cpdts.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.security.Permissions;
import com.cpdts.web.InvoiceManagementController.InvoiceSummaryDto;
import com.cpdts.web.PaymentManagementController.PaymentDto;
import com.cpdts.web.ReportingController.Basis;
import com.cpdts.web.ReportingController.CustomerDebt;
import com.cpdts.web.ReportingController.Dashboard;
import com.cpdts.web.ReportingController.Page;

@Service
public class ReportingService {
    private final JdbcTemplate jdbc;
    private final AccessControlService access;
    private final Clock clock;
    private final TransactionTemplate snapshot;

    public ReportingService(JdbcTemplate jdbc, AccessControlService access, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(transactionManager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private void authorize(long companyId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CpdtsPrincipal p)) {
            throw new AccessDeniedException("no authenticated actor");
        }
        access.requirePermission(p.userId(), companyId, Permissions.REPORT_VIEW);
    }

    private Basis basis(String kind, LocalDate from, LocalDate to) {
        Instant now = clock.instant();
        return new Basis(kind, now, now.atZone(clock.getZone()).toLocalDate(),
                clock.getZone().getId(), "TZS", from, to);
    }

    // One row per invoice and one row per verified payment: no invoice/payment join fan-out.
    private static final String CTE = """
            WITH invoice_balances AS (
              SELECT i.*, GREATEST(i.total_amount - %s, 0) AS balance
              FROM invoices i WHERE i.company_id = ? AND i.status = 'ISSUED'
            ), payment_balances AS (
              SELECT p.*, GREATEST(p.amount - %s, 0) AS credit
              FROM payments p WHERE p.company_id = ? AND p.status = 'VERIFIED'
            )
            """.formatted(InvoiceManagementService.ALLOCATED_SUM_SQL,
                    PaymentManagementService.PAYMENT_ALLOCATED_SUM_SQL);

    public Dashboard dashboard(long companyId) {
        return snapshot.execute(tx -> {
            authorize(companyId);
            Basis basis = basis("CURRENT_BALANCE", null, null);
            return jdbc.queryForObject(CTE + """
                    SELECT (SELECT count(*) FROM invoice_balances WHERE balance > 0) outstanding_count,
                           (SELECT coalesce(sum(balance),0) FROM invoice_balances) outstanding,
                           (SELECT count(*) FROM invoice_balances WHERE balance > 0 AND due_date < ?) overdue_count,
                           (SELECT coalesce(sum(balance),0) FROM invoice_balances WHERE due_date < ?) overdue,
                           (SELECT count(*) FROM payments WHERE company_id = ? AND status = 'PENDING_VERIFICATION') pending_count,
                           (SELECT coalesce(sum(amount),0) FROM payments WHERE company_id = ? AND status = 'PENDING_VERIFICATION') pending,
                           (SELECT coalesce(sum(amount),0) FROM payment_balances) verified,
                           (SELECT coalesce(sum(credit),0) FROM payment_balances) credit
                    """, (rs, n) -> new Dashboard(basis, rs.getLong("outstanding_count"),
                    rs.getBigDecimal("outstanding"), rs.getLong("overdue_count"),
                    rs.getBigDecimal("overdue"), rs.getLong("pending_count"), rs.getBigDecimal("pending"),
                    rs.getBigDecimal("verified"), rs.getBigDecimal("credit")), companyId, companyId,
                    basis.businessDate(), basis.businessDate(), companyId, companyId);
        });
    }

    public Page<CustomerDebt> customers(long companyId, Integer page, Integer size, String q) {
        return snapshot.execute(tx -> {
            authorize(companyId);
            int[] pg = pagination(page, size);
            Basis basis = basis("CURRENT_BALANCE", null, null);
            String search = pattern(q);
            String where = " WHERE c.company_id = ?" + (search == null ? "" :
                    " AND (c.display_name ILIKE ? ESCAPE '\\' OR c.customer_code ILIKE ? ESCAPE '\\')");
            List<Object> filters = new ArrayList<>(List.of(companyId));
            if (search != null) { filters.add(search); filters.add(search); }
            long count = jdbc.queryForObject("SELECT count(*) FROM customers c" + where, Long.class, filters.toArray());
            List<Object> args = new ArrayList<>(List.of(companyId, companyId, basis.businessDate()));
            args.addAll(filters); args.add(pg[1]); args.add((long) pg[0] * pg[1]);
            List<CustomerDebt> items = jdbc.query(CTE + """
                    , debts AS (SELECT customer_id, sum(balance) outstanding,
                        sum(balance) FILTER (WHERE due_date < ?) overdue
                        FROM invoice_balances GROUP BY customer_id),
                    credits AS (SELECT customer_id, sum(credit) credit FROM payment_balances GROUP BY customer_id)
                    SELECT c.id, c.customer_code, c.display_name, c.status,
                           coalesce(d.outstanding,0) outstanding, coalesce(d.overdue,0) overdue,
                           coalesce(cr.credit,0) credit
                    FROM customers c LEFT JOIN debts d ON d.customer_id = c.id
                    LEFT JOIN credits cr ON cr.customer_id = c.id
                    """ + where + " ORDER BY c.display_name, c.id LIMIT ? OFFSET ?",
                    (rs, n) -> new CustomerDebt(rs.getLong("id"), rs.getString("customer_code"),
                            rs.getString("display_name"), rs.getString("status"),
                            rs.getBigDecimal("outstanding"), rs.getBigDecimal("overdue"),
                            rs.getBigDecimal("credit")), args.toArray());
            return result(basis, items, pg, count);
        });
    }

    public Page<InvoiceSummaryDto> invoices(long companyId, Integer page, Integer size,
            String q, Long customerId, boolean overdueOnly) {
        return snapshot.execute(tx -> {
            authorize(companyId);
            int[] pg = pagination(page, size);
            Basis basis = basis("CURRENT_BALANCE", null, null);
            StringBuilder where = new StringBuilder(" WHERE i.company_id = ? AND i.status = 'ISSUED' AND i.total_amount > ")
                    .append(InvoiceManagementService.ALLOCATED_SUM_SQL);
            List<Object> args = new ArrayList<>(List.of(companyId));
            if (customerId != null) { where.append(" AND i.customer_id = ?"); args.add(customerId); }
            if (overdueOnly) { where.append(" AND i.due_date < ?"); args.add(basis.businessDate()); }
            String search = pattern(q);
            if (search != null) {
                where.append(" AND (i.invoice_number ILIKE ? ESCAPE '\\' OR i.description ILIKE ? ESCAPE '\\')");
                args.add(search); args.add(search);
            }
            long count = jdbc.queryForObject("SELECT count(*) FROM invoices i" + where, Long.class, args.toArray());
            List<Object> queryArgs = new ArrayList<>(args);
            queryArgs.add(pg[1]); queryArgs.add((long) pg[0] * pg[1]);
            List<InvoiceSummaryDto> items = jdbc.query("""
                    SELECT i.id, i.invoice_number, i.customer_id, i.issue_date, i.due_date,
                           i.currency_code, i.status, i.total_amount, i.description,
                           i.issued_at, i.voided_at, i.created_at, i.updated_at,
                           c.customer_code, c.display_name AS customer_name, %s AS allocated_amount
                    FROM invoices i JOIN customers c ON c.id=i.customer_id AND c.company_id=i.company_id
                    """.formatted(InvoiceManagementService.ALLOCATED_SUM_SQL) + where
                    + " ORDER BY i.due_date, i.id LIMIT ? OFFSET ?",
                    (rs, n) -> InvoiceManagementService.mapSummary(rs, basis.businessDate()), queryArgs.toArray());
            return result(basis, items, pg, count);
        });
    }

    public Page<PaymentDto> payments(long companyId, Integer page, Integer size, String q,
            String status, String method, Long customerId, LocalDate from, LocalDate to) {
        return snapshot.execute(tx -> {
            authorize(companyId);
            int[] pg = pagination(page, size);
            validateDate(from); validateDate(to);
            if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
            Basis basis = basis("PAYMENT_ACTIVITY", from, to);
            String s = option(status, Set.of("PENDING_VERIFICATION", "VERIFIED", "REJECTED", "REVERSED"), "status");
            String m = option(method, Set.of("CASH", "BANK_TRANSFER", "MOBILE_MONEY"), "method");
            StringBuilder where = new StringBuilder(" WHERE p.company_id = ?");
            List<Object> args = new ArrayList<>(List.of(companyId));
            if (s != null) { where.append(" AND p.status = ?"); args.add(s); }
            if (m != null) { where.append(" AND p.method = ?"); args.add(m); }
            if (customerId != null) { where.append(" AND p.customer_id = ?"); args.add(customerId); }
            if (from != null) { where.append(" AND p.received_at >= ?"); args.add(Timestamp.from(from.atStartOfDay(clock.getZone()).toInstant())); }
            if (to != null) { where.append(" AND p.received_at < ?"); args.add(Timestamp.from(to.plusDays(1).atStartOfDay(clock.getZone()).toInstant())); }
            String search = pattern(q);
            if (search != null) {
                where.append(" AND (p.payment_number ILIKE ? ESCAPE '\\' OR p.reference_number ILIKE ? ESCAPE '\\' OR p.notes ILIKE ? ESCAPE '\\')");
                args.add(search); args.add(search); args.add(search);
            }
            long count = jdbc.queryForObject("SELECT count(*) FROM payments p" + where, Long.class, args.toArray());
            List<Object> queryArgs = new ArrayList<>(args);
            queryArgs.add(pg[1]); queryArgs.add((long) pg[0] * pg[1]);
            List<PaymentDto> items = jdbc.query(PaymentManagementService.PAYMENT_SELECT + where
                    + " ORDER BY p.received_at DESC, p.id DESC LIMIT ? OFFSET ?",
                    (rs, n) -> PaymentManagementService.mapPayment(rs), queryArgs.toArray());
            return result(basis, items, pg, count);
        });
    }

    private static int[] pagination(Integer page, Integer size) {
        int p = page == null ? 0 : page, s = size == null ? 20 : size;
        if (p < 0 || s < 1 || s > 100) throw new IllegalArgumentException("page must be >= 0 and size between 1 and 100");
        return new int[] { p, s };
    }
    private static <T> Page<T> result(Basis b, List<T> items, int[] pg, long count) {
        return new Page<>(b, items, pg[0], pg[1], count, (count + pg[1] - 1) / pg[1]);
    }
    private static String pattern(String q) {
        if (q == null || q.isBlank()) return null;
        return "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    private static String option(String raw, Set<String> valid, String name) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!valid.contains(value)) throw new IllegalArgumentException("invalid " + name);
        return value;
    }
    private static void validateDate(LocalDate date) {
        if (date != null && (date.getYear() < 2000 || date.getYear() > 2100))
            throw new IllegalArgumentException("dates must be between 2000 and 2100");
    }
}
