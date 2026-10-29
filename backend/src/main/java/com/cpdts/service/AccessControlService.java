package com.cpdts.service;

import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.cpdts.repository.UserRepository;
import com.cpdts.repository.UserRepository.UserRow;
import com.cpdts.security.Permissions;

/**
 * Central server-side authorization service.
 *
 * <p>Every protected business operation (current and future) must call
 * {@link #requirePermission(long, long, String)} (or the query methods) with
 * the acting user and the target company. Rules enforced here:
 * <ul>
 *   <li>Only ACTIVE users are granted anything.</li>
 *   <li>Only ACTIVE memberships in ACTIVE companies grant permissions.</li>
 *   <li>Permissions come from the membership's role via role_permissions
 *       (permission-based authorization, not hard-coded role names).</li>
 *   <li>Platform admins (users.is_platform_admin = TRUE) have every permission
 *       in every company WITHOUT needing memberships - the approved
 *       platform-wide access rule.</li>
 *   <li>A user without a membership in company X has NO permissions in
 *       company X (company isolation), even if they admin another company.</li>
 * </ul>
 */
@Service
public class AccessControlService {

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public AccessControlService(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /** True only if the user exists and users.status = 'ACTIVE'. */
    public boolean isUserActive(long userId) {
        return users.findById(userId).map(UserRow::isActive).orElse(false);
    }

    /**
     * The permissions the user currently holds in the given company.
     *
     * @throws AccessDeniedException if the user is unknown/inactive or the
     *         company is unknown (fail closed).
     */
    public Set<String> permissionsFor(long userId, long companyId) {
        UserRow user = users.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("unknown user"));
        if (!user.isActive()) {
            throw new AccessDeniedException("user is not active");
        }
        requireCompanyExists(companyId);

        if (user.platformAdmin()) {
            // Platform-wide access: all current permissions, no membership needed.
            return Permissions.ALL;
        }

        List<String> codes = jdbc.query("""
                SELECT p.code
                FROM company_memberships m
                JOIN companies c        ON c.id = m.company_id
                JOIN role_permissions rp ON rp.role_id = m.role_id
                JOIN permissions p      ON p.id = rp.permission_id
                WHERE m.user_id = ?
                  AND m.company_id = ?
                  AND m.status = 'ACTIVE'
                  AND c.status = 'ACTIVE'
                ORDER BY p.code
                """, (rs, rowNum) -> rs.getString(1), userId, companyId);
        return Set.copyOf(codes);
    }

    /**
     * Guard for a single operation: throws AccessDeniedException (mapped to
     * HTTP 403) unless the user currently holds the permission in the company.
     */
    public void requirePermission(long userId, long companyId, String permission) {
        if (!permissionsFor(userId, companyId).contains(permission)) {
            throw new AccessDeniedException("permission not granted: " + permission);
        }
    }

    /**
     * Companies the user can currently work with:
     * <ul>
     *   <li>platform admin: every company (platform-wide oversight); the role
     *       column is the membership role if an active membership exists,
     *       otherwise {@code null} (platform-wide, not a membership);</li>
     *   <li>everyone else: only companies with an ACTIVE membership, with the
     *       membership's role code.</li>
     * </ul>
     */
    public List<CompanyAccess> companiesFor(long userId) {
        UserRow user = users.findById(userId)
                .orElseThrow(() -> new AccessDeniedException("unknown user"));
        if (!user.isActive()) {
            throw new AccessDeniedException("user is not active");
        }

        if (user.platformAdmin()) {
            return jdbc.query("""
                    SELECT c.id, c.code, c.display_name, r.code AS role_code
                    FROM companies c
                    LEFT JOIN company_memberships m
                           ON m.company_id = c.id AND m.user_id = ? AND m.status = 'ACTIVE'
                    LEFT JOIN roles r ON r.id = m.role_id
                    ORDER BY c.code
                    """, (rs, rowNum) -> mapCompany(rs), userId);
        }

        return jdbc.query("""
                SELECT c.id, c.code, c.display_name, r.code AS role_code
                FROM companies c
                JOIN company_memberships m
                  ON m.company_id = c.id AND m.user_id = ? AND m.status = 'ACTIVE'
                JOIN roles r ON r.id = m.role_id
                WHERE c.status = 'ACTIVE'
                ORDER BY c.code
                """, (rs, rowNum) -> mapCompany(rs), userId);
    }

    private void requireCompanyExists(long companyId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM companies WHERE id = ?", Integer.class, companyId);
        if (count == null || count == 0) {
            throw new AccessDeniedException("unknown company");
        }
    }

    private static CompanyAccess mapCompany(java.sql.ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong("id");
        String code = rs.getString("code");
        String displayName = rs.getString("display_name");
        String roleCode = rs.getString("role_code");
        return new CompanyAccess(id, code, displayName, roleCode);
    }

    /** One company the user may access, with their role in it (nullable). */
    public record CompanyAccess(long companyId, String code, String displayName, String roleCode) {
    }
}
