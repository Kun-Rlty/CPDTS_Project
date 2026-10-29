package com.cpdts.service;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.security.Permissions;
import com.cpdts.web.CustomerManagementController.CreateCustomerRequest;
import com.cpdts.web.CustomerManagementController.CustomerDto;
import com.cpdts.web.CustomerManagementController.CustomerPage;
import com.cpdts.web.CustomerManagementController.UpdateCustomerRequest;
import com.cpdts.web.NotFoundException;

import tools.jackson.databind.ObjectMapper;

/**
 * Company-scoped customer management (PRD FR-03; implementation-plan Phase 4):
 * create, list/search, retrieve, update and activate/deactivate customers.
 *
 * <p>Tenant rules (server-side, on every call, before any lookup):
 * <ul>
 *   <li>Reads require {@code RECORD_VIEW}, mutations require
 *       {@code CUSTOMER_MANAGE} in the TARGET company - resolved per request
 *       by {@link AccessControlService} (active user, active membership in an
 *       active company; platform admins hold every permission everywhere).</li>
 *   <li>Every lookup and write is scoped by {@code company_id}: a customer of
 *       another company is indistinguishable from a nonexistent one (404), so
 *       this API never discloses other companies' customers.</li>
 *   <li>Customers are NEVER deleted (no DELETE endpoint; the V2 database
 *       trigger blocks it as well) - deactivation is a status change.</li>
 * </ul>
 *
 * <p>Customer codes are generated SERVER-SIDE from the company's
 * {@code number_sequences} row (kind CUSTOMER) inside the same transaction as
 * the insert: the counter row is locked (row lock via INSERT..ON CONFLICT /
 * UPDATE) until commit, so concurrent creations receive distinct, contiguous
 * numbers. A client-supplied code is never read; company ownership always
 * comes from the path, after the permission check.
 *
 * <p>Audit: {@code CUSTOMER_CREATED} / {@code CUSTOMER_UPDATED} rows are
 * written in the SAME transaction as the change with the acting session user
 * as actor. Update events carry a minimized diff (changed fields only, never
 * {@code notes} - unbounded TEXT, not identity-relevant).
 */
@Service
public class CustomerManagementService {

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_INACTIVE = "INACTIVE";
    static final String SEQUENCE_KIND = "CUSTOMER";

    // Field bounds mirror the V2 schema exactly (plus one engineering bound).
    static final int MAX_DISPLAY_NAME = 255;
    static final int MAX_PHONE = 50;
    static final int MAX_EMAIL = 320;
    static final int MAX_ADDRESS = 500;
    /** Engineering bound on the unbounded TEXT column - not a business rule. */
    static final int MAX_NOTES = 10_000;

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;

    public CustomerManagementService(JdbcTemplate jdbc,
            AccessControlService accessControlService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.accessControlService = accessControlService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // Reads (RECORD_VIEW)
    // ------------------------------------------------------------------

    /**
     * One page of the company's customers, optionally filtered by status and
     * searched by display name / code / email / phone. User input is escaped
     * for ILIKE so wildcards typed by the user match literally.
     */
    public CustomerPage listCustomers(long companyId, Integer page, Integer size, String q,
            String status) {
        accessControlService.requirePermission(currentActorId(), companyId,
                Permissions.RECORD_VIEW);

        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!normalizedStatus.isEmpty() && !STATUS_ACTIVE.equals(normalizedStatus)
                && !STATUS_INACTIVE.equals(normalizedStatus)) {
            throw new IllegalArgumentException("status must be ACTIVE or INACTIVE");
        }

        String search = q == null ? "" : q.trim();
        String searchPattern = search.isEmpty() ? null : "%" + escapeLike(search) + "%";

        StringBuilder where = new StringBuilder("WHERE company_id = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(companyId);
        if (!normalizedStatus.isEmpty()) {
            where.append(" AND status = ?");
            args.add(normalizedStatus);
        }
        if (searchPattern != null) {
            where.append("""
                     AND (display_name ILIKE ? ESCAPE '\\'
                       OR customer_code ILIKE ? ESCAPE '\\'
                       OR email       ILIKE ? ESCAPE '\\'
                       OR phone       ILIKE ? ESCAPE '\\')
                    """);
            args.add(searchPattern);
            args.add(searchPattern);
            args.add(searchPattern);
            args.add(searchPattern);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM customers " + where,
                Long.class, args.toArray());
        List<CustomerDto> items = jdbc.query(
                """
                SELECT id, customer_code, display_name, phone, email, address, notes,
                       status, created_at, updated_at
                FROM customers %s
                ORDER BY display_name, id
                LIMIT ? OFFSET ?
                """.formatted(where),
                (rs, rowNum) -> mapCustomer(rs),
                concat(args, pageSize, (long) pageNumber * pageSize).toArray());

        long totalElements = total == null ? 0 : total;
        int totalPages = (int) ((totalElements + pageSize - 1) / pageSize);
        return new CustomerPage(items, pageNumber, pageSize, totalElements, totalPages);
    }

    public CustomerDto getCustomer(long companyId, long customerId) {
        accessControlService.requirePermission(currentActorId(), companyId,
                Permissions.RECORD_VIEW);
        return findInCompany(companyId, customerId)
                .orElseThrow(() -> new NotFoundException("customer not found in this company"));
    }

    // ------------------------------------------------------------------
    // Create (CUSTOMER_MANAGE) - code generated server-side
    // ------------------------------------------------------------------

    @Transactional
    public CustomerDto createCustomer(long companyId, CreateCustomerRequest request) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.CUSTOMER_MANAGE);

        String displayName = requireText(request == null ? null : request.displayName(),
                "display name is required", MAX_DISPLAY_NAME, "display name");
        String phone = optionalText(request == null ? null : request.phone(), MAX_PHONE, "phone");
        String email = optionalEmail(request == null ? null : request.email());
        String address = optionalText(request == null ? null : request.address(), MAX_ADDRESS,
                "address");
        String notes = optionalText(request == null ? null : request.notes(), MAX_NOTES, "notes");

        // Locks the company's CUSTOMER counter row until this transaction
        // commits: concurrent creations are serialized on it and receive
        // distinct, contiguous numbers. Also self-seeds a missing row.
        Long sequence = jdbc.queryForObject("""
                INSERT INTO number_sequences (company_id, kind, next_value)
                VALUES (?, ?, 2)
                ON CONFLICT (company_id, kind) DO UPDATE
                SET next_value = number_sequences.next_value + 1, updated_at = now()
                RETURNING next_value - 1
                """, Long.class, companyId, SEQUENCE_KIND);
        if (sequence == null) {
            throw new IllegalStateException("customer sequence returned no value");
        }
        String companyCode = jdbc.queryForObject(
                "SELECT code FROM companies WHERE id = ?", String.class, companyId);
        String customerCode = "%s-CUST-%05d".formatted(companyCode, sequence);

        Long customerId = jdbc.queryForObject("""
                INSERT INTO customers
                    (company_id, customer_code, display_name, phone, email, address, notes,
                     status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, companyId, customerCode, displayName, phone, email, address,
                notes, STATUS_ACTIVE, actorId);
        if (customerId == null) {
            throw new IllegalStateException("customer insert returned no id");
        }

        // Audit in the SAME transaction (PRD FR-09) - identity summary only.
        audit(actorId, companyId, "CUSTOMER_CREATED", customerId, null, Map.of(
                "customer_code", customerCode,
                "display_name", displayName,
                "status", STATUS_ACTIVE));

        return getInCompanyOrThrow(companyId, customerId);
    }

    // ------------------------------------------------------------------
    // Update / activate / deactivate (CUSTOMER_MANAGE)
    // ------------------------------------------------------------------

    @Transactional
    public CustomerDto updateCustomer(long companyId, long customerId,
            UpdateCustomerRequest request) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.CUSTOMER_MANAGE);

        boolean changeName = request != null && request.displayName() != null;
        boolean changePhone = request != null && request.phone() != null;
        boolean changeEmail = request != null && request.email() != null;
        boolean changeAddress = request != null && request.address() != null;
        boolean changeNotes = request != null && request.notes() != null;
        boolean changeStatus = request != null && request.status() != null;
        if (!changeName && !changePhone && !changeEmail && !changeAddress && !changeNotes
                && !changeStatus) {
            throw new IllegalArgumentException(
                    "at least one of display name, phone, email, address, notes or status "
                            + "is required");
        }

        String displayName = changeName
                ? requireText(request.displayName(), "display name is required", MAX_DISPLAY_NAME,
                        "display name")
                : null;
        String phone = changePhone ? optionalText(request.phone(), MAX_PHONE, "phone") : null;
        String email = changeEmail ? optionalEmail(request.email()) : null;
        String address = changeAddress ? optionalText(request.address(), MAX_ADDRESS, "address")
                : null;
        String notes = changeNotes ? optionalText(request.notes(), MAX_NOTES, "notes") : null;
        String status = null;
        if (changeStatus) {
            status = request.status().trim().toUpperCase();
            if (!STATUS_ACTIVE.equals(status) && !STATUS_INACTIVE.equals(status)) {
                throw new IllegalArgumentException("status must be ACTIVE or INACTIVE");
            }
        }

        // Lock the target row FOR UPDATE within this transaction so the
        // before/after audit diff reflects exactly what this change replaced.
        CustomerRow current = jdbc.query(
                """
                SELECT id, customer_code, display_name, phone, email, address, notes, status
                FROM customers
                WHERE company_id = ? AND id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new CustomerRow(
                        rs.getLong("id"), rs.getString("customer_code"),
                        rs.getString("display_name"), rs.getString("phone"),
                        rs.getString("email"), rs.getString("address"), rs.getString("notes"),
                        rs.getString("status")),
                companyId, customerId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("customer not found in this company"));

        Map<String, Object> before = new java.util.LinkedHashMap<>();
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("UPDATE customers SET updated_at = now(), "
                + "updated_by = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(actorId);
        if (changeName) {
            sql.append(", display_name = ?");
            args.add(displayName);
            putChange(before, after, "display_name", current.displayName(), displayName);
        }
        if (changePhone) {
            sql.append(", phone = ?");
            args.add(phone);
            putChange(before, after, "phone", current.phone(), phone);
        }
        if (changeEmail) {
            sql.append(", email = ?");
            args.add(email);
            putChange(before, after, "email", current.email(), email);
        }
        if (changeAddress) {
            sql.append(", address = ?");
            args.add(address);
            putChange(before, after, "address", current.address(), address);
        }
        if (changeNotes) {
            sql.append(", notes = ?");
            args.add(notes);
            // Deliberately NOT audited: notes is unbounded TEXT and not
            // identity-relevant (minimized audit data rule).
        }
        if (changeStatus) {
            sql.append(", status = ?");
            args.add(status);
            putChange(before, after, "status", current.status(), status);
        }
        sql.append(" WHERE company_id = ? AND id = ?");
        args.add(companyId);
        args.add(customerId);
        jdbc.update(sql.toString(), args.toArray());

        // Only actual changes produce an audit event (idempotent PATCHes are
        // silent, like the user-management role/status updates).
        if (!before.isEmpty()) {
            audit(actorId, companyId, "CUSTOMER_UPDATED", customerId, before, after);
        }

        return getInCompanyOrThrow(companyId, customerId);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private java.util.Optional<CustomerDto> findInCompany(long companyId, long customerId) {
        return jdbc.query(
                """
                SELECT id, customer_code, display_name, phone, email, address, notes,
                       status, created_at, updated_at
                FROM customers
                WHERE company_id = ? AND id = ?
                """, (rs, rowNum) -> mapCustomer(rs), companyId, customerId).stream()
                .findFirst();
    }

    private CustomerDto getInCompanyOrThrow(long companyId, long customerId) {
        return findInCompany(companyId, customerId)
                .orElseThrow(() -> new IllegalStateException(
                        "customer missing immediately after write"));
    }

    /** The authenticated actor, taken from the session security context. */
    private long currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof CpdtsPrincipal principal)) {
            throw new AccessDeniedException("no authenticated actor");
        }
        return principal.userId();
    }

    private static String requireText(String value, String blankMessage, int maxLength,
            String fieldLabel) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(blankMessage);
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldLabel + " must be at most " + maxLength
                    + " characters");
        }
        return normalized;
    }

    private static String optionalText(String value, int maxLength, String fieldLabel) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldLabel + " must be at most " + maxLength
                    + " characters");
        }
        return normalized;
    }

    /** Optional contact email: trimmed; a provided value must look like one. */
    private static String optionalEmail(String value) {
        String normalized = optionalText(value, MAX_EMAIL, "email");
        if (normalized != null && !normalized.contains("@")) {
            throw new IllegalArgumentException("email must contain @");
        }
        return normalized;
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void putChange(Map<String, Object> before, Map<String, Object> after,
            String field, Object oldValue, Object newValue) {
        if (java.util.Objects.equals(oldValue, newValue)) {
            return;
        }
        before.put(field, oldValue == null ? "" : oldValue);
        after.put(field, newValue == null ? "" : newValue);
    }

    private static java.util.List<Object> concat(java.util.List<Object> base, Object... extra) {
        java.util.List<Object> all = new java.util.ArrayList<>(base);
        for (Object value : extra) {
            all.add(value);
        }
        return all;
    }

    private static CustomerDto mapCustomer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CustomerDto(
                rs.getLong("id"),
                rs.getString("customer_code"),
                rs.getString("display_name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("address"),
                rs.getString("notes"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * One append-only audit row in the caller's transaction - it commits or
     * rolls back exactly with the change it describes. Minimized JSON: never
     * notes, never whole rows.
     */
    private void audit(long actorId, Long companyId, String action, Long customerId,
            Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO audit_logs
                    (company_id, actor_user_id, action, entity_type, entity_id,
                     before_data, after_data, reason)
                VALUES (?, ?, ?, 'CUSTOMER', ?, ?::jsonb, ?::jsonb, NULL)
                """,
                companyId, actorId, action, customerId,
                before == null ? null : objectMapper.writeValueAsString(before),
                after == null ? null : objectMapper.writeValueAsString(after));
    }

    private record CustomerRow(long id, String code, String displayName, String phone,
            String email, String address, String notes, String status) {
    }
}
