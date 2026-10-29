package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import com.cpdts.security.Permissions;
import com.cpdts.service.AccessControlService.CompanyAccess;
import com.cpdts.support.CpdtsIntegrationTest;

/**
 * Server-side authorization tests: the approved permission matrix, company
 * isolation, active-membership rules, the platform-admin bypass, multiple
 * users sharing a role, and different roles in different companies.
 */
class AccessControlServiceTest extends CpdtsIntegrationTest {

    private static final Set<String> ALL_SEVEN = Set.of(
            Permissions.USER_MANAGE, Permissions.CUSTOMER_MANAGE, Permissions.INVOICE_MANAGE,
            Permissions.PAYMENT_RECORD, Permissions.PAYMENT_VERIFY, Permissions.RECORD_VIEW,
            Permissions.REPORT_VIEW);

    @AfterEach
    void cleanUpTestUsers() {
        deleteAllTestUsers();
    }

    @Test
    void seededPermissionMatrixMatchesTheApprovedRolesExactly() {
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("PLATFORM_ADMIN", ALL_SEVEN);
        expected.put("COMPANY_ADMIN", ALL_SEVEN);
        expected.put("FINANCE_OFFICER",
                Set.of(Permissions.CUSTOMER_MANAGE, Permissions.INVOICE_MANAGE,
                        Permissions.PAYMENT_RECORD, Permissions.RECORD_VIEW,
                        Permissions.REPORT_VIEW));
        expected.put("PAYMENT_VERIFIER",
                Set.of(Permissions.PAYMENT_VERIFY, Permissions.RECORD_VIEW,
                        Permissions.REPORT_VIEW));
        expected.put("VIEWER", Set.of(Permissions.RECORD_VIEW, Permissions.REPORT_VIEW));

        // The permission catalogue is exactly the seven approved codes.
        assertThat(jdbc.queryForList("SELECT code FROM permissions", String.class))
                .containsExactlyInAnyOrderElementsOf(Permissions.ALL);

        // No extra roles exist beyond the approved five.
        assertThat(jdbc.queryForList("SELECT code FROM roles", String.class))
                .containsExactlyInAnyOrderElementsOf(expected.keySet());

        // Each role grants exactly the approved permissions.
        for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
            Set<String> actual = new HashSet<>(jdbc.queryForList("""
                    SELECT p.code
                    FROM role_permissions rp
                    JOIN roles r       ON r.id = rp.role_id
                    JOIN permissions p ON p.id = rp.permission_id
                    WHERE r.code = ?
                    """, String.class, entry.getKey()));
            assertThat(actual).as("permissions of role %s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    void platformAdminHasEveryPermissionInBothCompaniesWithoutAnyMembership() {
        long userId = insertUser(uniqueEmail("padmin"), "platform admin password 1", "ACTIVE",
                true);

        assertThat(accessControlService.permissionsFor(userId, companyId("JMX")))
                .isEqualTo(ALL_SEVEN);
        assertThat(accessControlService.permissionsFor(userId, companyId("KAM")))
                .isEqualTo(ALL_SEVEN);
        assertThat(accessControlService.companiesFor(userId))
                .extracting(CompanyAccess::code).containsExactly("JMX", "KAM");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ?", Long.class, userId))
                .isZero();
    }

    @Test
    void userWithoutMembershipInAnotherCompanyHasNoAccessToIt() {
        long userId = insertUser(uniqueEmail("jmx-only"), "jmx officer password 1", "ACTIVE",
                false);
        addMembership(userId, "JMX", "FINANCE_OFFICER", "ACTIVE");

        // Full finance permissions in JMX:
        assertThat(accessControlService.permissionsFor(userId, companyId("JMX")))
                .isEqualTo(Set.of(Permissions.CUSTOMER_MANAGE, Permissions.INVOICE_MANAGE,
                        Permissions.PAYMENT_RECORD, Permissions.RECORD_VIEW,
                        Permissions.REPORT_VIEW));

        // Nothing at all in KAM (company isolation):
        assertThat(accessControlService.permissionsFor(userId, companyId("KAM"))).isEmpty();
        assertThatThrownBy(() -> accessControlService.requirePermission(userId, companyId("KAM"),
                Permissions.RECORD_VIEW)).isInstanceOf(AccessDeniedException.class);

        // The company list exposes only JMX.
        assertThat(accessControlService.companiesFor(userId))
                .extracting(CompanyAccess::code).containsExactly("JMX");
    }

    @Test
    void multipleUsersCanShareTheSameRoleInTheSameCompany() {
        long user1 = insertUser(uniqueEmail("shared-1"), "shared role password 1", "ACTIVE",
                false);
        long user2 = insertUser(uniqueEmail("shared-2"), "shared role password 2", "ACTIVE",
                false);
        addMembership(user1, "KAM", "FINANCE_OFFICER", "ACTIVE");
        addMembership(user2, "KAM", "FINANCE_OFFICER", "ACTIVE");

        Set<String> permissions1 = accessControlService.permissionsFor(user1, companyId("KAM"));
        Set<String> permissions2 = accessControlService.permissionsFor(user2, companyId("KAM"));
        assertThat(permissions1).as("both users share the role's permissions")
                .isEqualTo(permissions2).hasSize(5);

        // Users remain independent: disabling one never affects the other.
        setUserStatus(user1, "DISABLED");
        assertThatThrownBy(() -> accessControlService.permissionsFor(user1, companyId("KAM")))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(accessControlService.permissionsFor(user2, companyId("KAM")))
                .isEqualTo(permissions2);
    }

    @Test
    void inactiveMembershipGrantsNothing() {
        long userId = insertUser(uniqueEmail("inactive-member"), "inactive member password 1",
                "ACTIVE", false);
        addMembership(userId, "JMX", "COMPANY_ADMIN", "INACTIVE");

        assertThat(accessControlService.permissionsFor(userId, companyId("JMX"))).isEmpty();
        assertThat(accessControlService.companiesFor(userId)).isEmpty();
        assertThatThrownBy(() -> accessControlService.requirePermission(userId, companyId("JMX"),
                Permissions.USER_MANAGE)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void inactiveUsersAreDeniedEvenWithAnActiveMembership() {
        long userId = insertUser(uniqueEmail("disabled-member"), "disabled member password 1",
                "ACTIVE", false);
        addMembership(userId, "JMX", "VIEWER", "ACTIVE");
        setUserStatus(userId, "DISABLED");

        assertThat(accessControlService.isUserActive(userId)).isFalse();
        assertThatThrownBy(() -> accessControlService.permissionsFor(userId, companyId("JMX")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessControlService.companiesFor(userId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void oneUserCanHoldDifferentRolesInDifferentCompanies() {
        long userId = insertUser(uniqueEmail("dual-role"), "dual role password 1", "ACTIVE",
                false);
        addMembership(userId, "JMX", "FINANCE_OFFICER", "ACTIVE");
        addMembership(userId, "KAM", "VIEWER", "ACTIVE");

        assertThat(accessControlService.permissionsFor(userId, companyId("JMX")))
                .as("FINANCE_OFFICER permissions in JMX")
                .isEqualTo(Set.of(Permissions.CUSTOMER_MANAGE, Permissions.INVOICE_MANAGE,
                        Permissions.PAYMENT_RECORD, Permissions.RECORD_VIEW,
                        Permissions.REPORT_VIEW));
        assertThat(accessControlService.permissionsFor(userId, companyId("KAM")))
                .as("VIEWER permissions in KAM")
                .isEqualTo(Set.of(Permissions.RECORD_VIEW, Permissions.REPORT_VIEW));

        assertThat(accessControlService.companiesFor(userId))
                .containsExactly(
                        new CompanyAccess(companyId("JMX"), "JMX",
                                "JEIMAX INVESTMENTS COMPANY", "FINANCE_OFFICER"),
                        new CompanyAccess(companyId("KAM"), "KAM",
                                "KAMWERUKA SECURITY & SAFETY ADMINISTRATOR CO. LTD.", "VIEWER"));
    }

    @Test
    void theDatabaseEnforcesOneRolePerCompanyMembership() {
        long userId = insertUser(uniqueEmail("one-role"), "one role password 1", "ACTIVE", false);
        addMembership(userId, "JMX", "VIEWER", "ACTIVE");

        // A second role in the SAME company violates the V1 unique constraint.
        assertThatThrownBy(() -> addMembership(userId, "JMX", "FINANCE_OFFICER", "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void platformAdminRoleMembershipDoesNotGrantPlatformWideAccess() {
        // The PLATFORM_ADMIN ROLE is not the platform-admin FLAG: without
        // users.is_platform_admin, permissions come only from memberships and
        // stay confined to the member company. This is the escalation boundary:
        // nothing in the API can turn a company-scoped role into platform-wide
        // access (user/membership management APIs do not exist in this phase).
        long userId = insertUser(uniqueEmail("role-not-flag"), "role not flag password 1",
                "ACTIVE", false);
        addMembership(userId, "JMX", "PLATFORM_ADMIN", "ACTIVE");

        assertThat(accessControlService.permissionsFor(userId, companyId("JMX")))
                .as("the role grants its permissions inside JMX")
                .isEqualTo(ALL_SEVEN);
        assertThat(accessControlService.permissionsFor(userId, companyId("KAM")))
                .as("the same role membership must grant NOTHING in KAM")
                .isEmpty();
        assertThat(accessControlService.companiesFor(userId))
                .extracting(CompanyAccess::code).containsExactly("JMX");
        assertThat(jdbc.queryForObject(
                "SELECT is_platform_admin FROM users WHERE id = ?", Boolean.class, userId))
                .isFalse();
    }

    @Test
    void companyAdminPermissionsStayConfinedToTheirCompany() {
        // COMPANY_ADMIN is the strongest company-scoped role; it must remain
        // company-scoped in both companies and never become platform-wide.
        long userId = insertUser(uniqueEmail("co-admin"), "company admin password 1", "ACTIVE",
                false);
        addMembership(userId, "JMX", "COMPANY_ADMIN", "ACTIVE");

        assertThat(accessControlService.permissionsFor(userId, companyId("JMX")))
                .isEqualTo(ALL_SEVEN);
        assertThat(accessControlService.permissionsFor(userId, companyId("KAM")))
                .as("company admins get nothing in the other company").isEmpty();
        assertThat(accessControlService.companiesFor(userId))
                .extracting(CompanyAccess::code).containsExactly("JMX");
    }

    @Test
    void platformAdminFlagRevocationIsHonoredOnSubsequentChecks() {
        // Authorization reads the platform-admin flag fresh on every check:
        // revoking it takes effect on the next call without re-login (the
        // user then needs memberships for any company access at all).
        long userId = insertUser(uniqueEmail("flag-revoke"), "flag revoke password 1", "ACTIVE",
                true);
        assertThat(accessControlService.permissionsFor(userId, companyId("KAM")))
                .isEqualTo(ALL_SEVEN);

        jdbc.update("UPDATE users SET is_platform_admin = FALSE, updated_at = now() "
                + "WHERE id = ?", userId);

        assertThat(accessControlService.permissionsFor(userId, companyId("KAM")))
                .as("revoked platform admins lose platform-wide access immediately")
                .isEmpty();
        assertThat(accessControlService.companiesFor(userId)).isEmpty();
    }

    @Test
    void unknownCompanyOrUserFailsClosed() {
        long userId = insertUser(uniqueEmail("fail-closed"), "fail closed password 1", "ACTIVE",
                false);

        assertThatThrownBy(() -> accessControlService.permissionsFor(userId, 999_999L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accessControlService.permissionsFor(999_999L, companyId("JMX")))
                .isInstanceOf(AccessDeniedException.class);
    }
}
