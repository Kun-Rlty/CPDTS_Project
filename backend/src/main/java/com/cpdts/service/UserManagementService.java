package com.cpdts.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import com.cpdts.repository.UserRepository;
import com.cpdts.repository.UserRepository.UserRow;
import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.security.EmailNormalizer;
import com.cpdts.security.PasswordPolicy;
import com.cpdts.security.Permissions;
import com.cpdts.web.ConflictException;
import com.cpdts.web.NotFoundException;
import com.cpdts.web.UserManagementController.MemberDto;

/**
 * User and company-membership management (approved scope: list company users,
 * create a user with a membership, change the membership role, activate/
 * deactivate memberships).
 *
 * <p>Authorization rules (enforced server-side on every call, before any
 * lookup):
 * <ul>
 *   <li>All operations require {@code USER_MANAGE} in the TARGET company:
 *       platform admins hold it in every company, company admins only in
 *       companies where they hold an active membership (cross-company calls
 *       fail with 403).</li>
 *   <li>The platform-admin role and the {@code is_platform_admin} flag are
 *       NEVER assignable through this API - only the one-time bootstrap
 *       writes the flag.</li>
 *   <li>Adding an EXISTING user to a company requires the platform
 *       administrator (company admins cannot pull users across companies and
 *       no global user directory is exposed to them). To avoid disclosing
 *       whether an email belongs to an account OUTSIDE their company, a
 *       company admin receives the same generic 409 conflict for every
 *       already-registered email. Credentials of existing users are never
 *       changed here: no password may be supplied.</li>
 *   <li>The only user-global write is the creation of a brand-new account
 *       (ACTIVE, non-platform-admin, password validated by PasswordPolicy and
 *       stored as a BCrypt hash). Global status/profile of existing users is
 *       intentionally NOT mutable through this API.</li>
 * </ul>
 *
 * <p>Every mutation and its audit event run in ONE transaction
 * ({@link Transactional}): either both exist afterwards, or neither does.
 * The audit actor is derived from the authenticated session principal.
 *
 * <p>Concurrency: the V1 unique constraint (company_id, user_id) turns racing
 * duplicate membership inserts into a 409 instead of a second row.
 */
@Service
public class UserManagementService {

    private static final String MEMBERSHIP_ACTIVE = "ACTIVE";
    private static final String MEMBERSHIP_INACTIVE = "INACTIVE";
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    /**
     * The ONE message a company admin ever sees for an already-registered
     * email, whether the account is in their own company or anywhere else in
     * the system (disclosure guard - see createUser).
     */
    private static final String EMAIL_ALREADY_IN_USE = "a user with this email already exists";

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AccessControlService accessControlService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public UserManagementService(JdbcTemplate jdbc, UserRepository users,
            AccessControlService accessControlService, PasswordEncoder passwordEncoder,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.users = users;
        this.accessControlService = accessControlService;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    /**
     * Members of one company (both membership statuses, so managers can see
     * and reactivate deactivated memberships). Password hashes are never
     * selected, let alone returned.
     */
    public List<MemberDto> listUsers(long companyId) {
        accessControlService.requirePermission(currentActorId(), companyId,
                Permissions.USER_MANAGE);
        return jdbc.query("""
                SELECT u.id, u.email, u.full_name, u.status AS user_status,
                       r.code AS role, m.status AS membership_status
                FROM company_memberships m
                JOIN users u ON u.id = m.user_id
                JOIN roles r ON r.id = m.role_id
                WHERE m.company_id = ?
                ORDER BY u.full_name, u.email
                """, (rs, rowNum) -> new MemberDto(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("user_status"),
                        rs.getString("role"),
                        rs.getString("membership_status")),
                companyId);
    }

    // ------------------------------------------------------------------
    // Create user + membership (or attach an existing user: platform admin)
    // ------------------------------------------------------------------

    @Transactional
    public MemberDto createUser(long companyId, String email, String fullName, String password,
            String role) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.USER_MANAGE);

        // Input normalization via the shared helper (same rules as login and
        // the bootstrap); role uppercased (roles.code is upper by V1 check).
        String normalizedEmail = EmailNormalizer.normalize(email);
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (normalizedEmail.isEmpty()) {
            throw new IllegalArgumentException("email is required");
        }
        if (normalizedEmail.length() > 320 || !normalizedEmail.contains("@")) {
            throw new IllegalArgumentException("a valid email is required");
        }
        long roleId = requireAssignableRole(normalizedRole);

        boolean passwordProvided = password != null && !password.isBlank();
        Optional<UserRow> existing = users.findByEmail(normalizedEmail);

        long userId;
        boolean userCreated = false;
        String createdFullName = null;
        if (existing.isPresent()) {
            // Attaching an EXISTING user to a company: platform admin only,
            // and never with a password (no credential resets in this API).
            // fullName/profile data is ignored - it belongs to the existing
            // account and is not rewritten by an attach.
            if (!isPlatformAdmin(actorId)) {
                // Disclosure guard: a company admin gets the SAME generic
                // conflict whether the account is in their own company or
                // anywhere else in the system, so this endpoint cannot be
                // used to probe for registered emails outside their company.
                throw new ConflictException(EMAIL_ALREADY_IN_USE);
            }
            if (passwordProvided) {
                throw new IllegalArgumentException(
                        "password must not be provided when adding an existing user");
            }
            userId = existing.get().id();
        } else {
            // Profile validation applies only where the profile is written.
            String normalizedFullName = fullName == null ? "" : fullName.trim();
            if (normalizedFullName.isEmpty()) {
                throw new IllegalArgumentException("full name is required");
            }
            if (normalizedFullName.length() > 255) {
                throw new IllegalArgumentException("full name must be at most 255 characters");
            }
            if (!passwordProvided) {
                throw new IllegalArgumentException("password is required for a new user");
            }
            // Existing provisional policy: 12-72 UTF-8 bytes (bcrypt limit),
            // rejected up front - never truncated (IllegalArgumentException -> 400).
            PasswordPolicy.validate(password);
            try {
                userId = users.insert(normalizedEmail, passwordEncoder.encode(password),
                        normalizedFullName);
            } catch (DuplicateKeyException e) {
                // Race backstop: a concurrent create committed between the
                // email pre-check and this insert (emails are normalized, so
                // case-variants of one address collide here). Same generic
                // conflict as above - never a 500, never a different signal.
                throw new ConflictException(EMAIL_ALREADY_IN_USE);
            }
            createdFullName = normalizedFullName;
            userCreated = true;
        }

        long membershipId;
        try {
            membershipId = insertMembership(companyId, userId, roleId, MEMBERSHIP_ACTIVE, actorId);
        } catch (DuplicateKeyException e) {
            // V1 uq_memberships_company_user: one membership per company/user.
            // The transaction rolls back any new-user insert with it.
            throw new ConflictException("the user already has a membership in this company");
        }

        // Audit in the SAME transaction as the change (PRD FR-09). Minimized
        // identity data only - never the password or its hash.
        if (userCreated) {
            audit(actorId, companyId, "USER_CREATED", "USER", userId, null, Map.of(
                    "email", normalizedEmail,
                    "full_name", createdFullName,
                    "status", MEMBERSHIP_ACTIVE,
                    "is_platform_admin", false));
        }
        audit(actorId, companyId, "MEMBERSHIP_CREATED", "COMPANY_MEMBERSHIP", membershipId, null,
                Map.of("role", normalizedRole, "status", MEMBERSHIP_ACTIVE));

        return memberDto(companyId, userId);
    }

    // ------------------------------------------------------------------
    // Change membership role and/or status
    // ------------------------------------------------------------------

    @Transactional
    public MemberDto updateMembership(long companyId, long userId, String role, String status) {
        long actorId = currentActorId();
        accessControlService.requirePermission(actorId, companyId, Permissions.USER_MANAGE);

        boolean changeRole = role != null && !role.isBlank();
        boolean changeStatus = status != null && !status.isBlank();
        if (!changeRole && !changeStatus) {
            throw new IllegalArgumentException("role or status is required");
        }

        MembershipRow current = jdbc.query("""
                SELECT m.id, m.role_id, m.status, r.code AS role_code
                FROM company_memberships m
                JOIN roles r ON r.id = m.role_id
                WHERE m.company_id = ? AND m.user_id = ?
                """, (rs, rowNum) -> new MembershipRow(
                        rs.getLong("id"),
                        rs.getLong("role_id"),
                        rs.getString("status"),
                        rs.getString("role_code")),
                companyId, userId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "the user has no membership in this company"));

        if (changeRole) {
            String newRoleCode = role.trim().toUpperCase(Locale.ROOT);
            long newRoleId = requireAssignableRole(newRoleCode);
            if (!newRoleCode.equals(current.roleCode())) {
                jdbc.update("""
                        UPDATE company_memberships
                        SET role_id = ?, updated_at = now()
                        WHERE company_id = ? AND user_id = ?
                        """, newRoleId, companyId, userId);
                audit(actorId, companyId, "MEMBERSHIP_ROLE_CHANGED", "COMPANY_MEMBERSHIP",
                        current.id(),
                        Map.of("role", current.roleCode()), Map.of("role", newRoleCode));
            }
        }

        if (changeStatus) {
            String newStatus = status.trim().toUpperCase(Locale.ROOT);
            if (!MEMBERSHIP_ACTIVE.equals(newStatus) && !MEMBERSHIP_INACTIVE.equals(newStatus)) {
                throw new IllegalArgumentException("status must be ACTIVE or INACTIVE");
            }
            if (!newStatus.equals(current.status())) {
                jdbc.update("""
                        UPDATE company_memberships
                        SET status = ?, updated_at = now()
                        WHERE company_id = ? AND user_id = ?
                        """, newStatus, companyId, userId);
                audit(actorId, companyId, "MEMBERSHIP_STATUS_CHANGED", "COMPANY_MEMBERSHIP",
                        current.id(),
                        Map.of("status", current.status()), Map.of("status", newStatus));
            }
        }

        return memberDto(companyId, userId);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Resolves and validates an assignable role code. {@code PLATFORM_ADMIN}
     * is refused with 403 (privilege escalation attempt); unknown codes with
     * 400. Everything except the platform-admin tier is assignable - roles
     * are shared freely (multiple users may hold the same role).
     */
    private long requireAssignableRole(String roleCode) {
        if (roleCode.isEmpty()) {
            throw new IllegalArgumentException("role is required");
        }
        if (PLATFORM_ADMIN_ROLE.equals(roleCode)) {
            throw new AccessDeniedException(
                    "the platform administrator role cannot be assigned through this API");
        }
        Long roleId = jdbc.query("SELECT id FROM roles WHERE code = ?",
                (rs, rowNum) -> rs.getLong(1), roleCode).stream().findFirst().orElse(null);
        if (roleId == null) {
            throw new IllegalArgumentException("unknown role: " + roleCode);
        }
        return roleId;
    }

    private long insertMembership(long companyId, long userId, long roleId, String status,
            long actorId) {
        Long membershipId = jdbc.queryForObject("""
                INSERT INTO company_memberships (company_id, user_id, role_id, status, created_by)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, companyId, userId, roleId, status, actorId);
        if (membershipId == null) {
            throw new IllegalStateException("membership insert returned no id");
        }
        return membershipId;
    }

    private MemberDto memberDto(long companyId, long userId) {
        List<MemberDto> found = jdbc.query("""
                SELECT u.id, u.email, u.full_name, u.status, r.code, m.status
                FROM company_memberships m
                JOIN users u ON u.id = m.user_id
                JOIN roles r ON r.id = m.role_id
                WHERE m.company_id = ? AND m.user_id = ?
                """, (rs, rowNum) -> new MemberDto(
                        rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)),
                companyId, userId);
        if (found.isEmpty()) {
            throw new IllegalStateException("membership missing immediately after write");
        }
        return found.get(0);
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

    private boolean isPlatformAdmin(long userId) {
        return users.findById(userId).map(UserRow::platformAdmin).orElse(false);
    }

    /**
     * One append-only audit row, written in the caller's transaction so it
     * commits/rolls back exactly with the change it describes. JSON is
     * minimized (changed/identity fields only - never passwords or hashes).
     */
    private void audit(long actorId, Long companyId, String action, String entityType,
            Long entityId, Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO audit_logs
                    (company_id, actor_user_id, action, entity_type, entity_id,
                     before_data, after_data, reason)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, NULL)
                """,
                companyId, actorId, action, entityType, entityId,
                toJson(before), toJson(after));
    }

    private String toJson(Map<String, Object> data) {
        // Jackson 3: writeValueAsString throws unchecked JacksonException only
        // for serialization failures (Maps of primitives/strings never fail).
        return data == null ? null : objectMapper.writeValueAsString(data);
    }

    private record MembershipRow(long id, long roleId, String status, String roleCode) {
    }
}
