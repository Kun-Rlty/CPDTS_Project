package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cpdts.support.CpdtsIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * API-level tests for the user/company-membership management endpoints:
 *
 * <ul>
 *   <li>authorized operations (platform admin in both companies, company
 *       admin in their own company),</li>
 *   <li>cross-company denial and privilege-escalation attempts
 *       (PLATFORM_ADMIN role, is_platform_admin flag, cross-company attach),</li>
 *   <li>shared roles, one-membership-per-company conflicts,</li>
 *   <li>CSRF, unauthenticated access, password policy for new accounts,</li>
 *   <li>immediate effect of membership role/status changes on the target
 *       user's live session and permissions,</li>
 *   <li>audit events with the session actor, no password-hash exposure.</li>
 * </ul>
 */
class UserManagementEndpointTest extends CpdtsIntegrationTest {

    private static final String ACTOR_PASSWORD = "fixture actor password 1";

    // Fixture actors use FIXED emails on purpose: audit rows are append-only
    // and reference their actor (FK), so an actor can never be deleted from
    // the test database - reusing one row per role keeps the DB bounded.
    // deleteAllTestUsers() clears their memberships and platform flag after
    // each test; the helpers below restore what each test needs.
    private static final String PLATFORM_ACTOR_EMAIL = "platform.actor@example.test";
    private static final String COADMIN_JMX_EMAIL = "coadmin.jmx.actor@example.test";
    private static final String COADMIN_KAM_EMAIL = "coadmin.kam.actor@example.test";

    @AfterEach
    void cleanUpTestUsers() {
        deleteAllTestUsers();
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    @Test
    void platformAdminListsUsersInBothCompaniesWithoutExposingHashes() throws Exception {
        long platformActor = ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);

        String memberEmail = uniqueEmail("listed");
        long memberId = insertUser(memberEmail, "listed user password 1", "ACTIVE", false);
        addMembership(memberId, "JMX", "VIEWER", "ACTIVE");

        MvcResult jmx = mockMvc.perform(listUsers(companyId("JMX"), session))
                .andExpect(status().isOk())
                .andReturn();
        String jmxBody = jmx.getResponse().getContentAsString();
        List<String> roles = JsonPath.read(jmxBody, "$[?(@.email=='" + memberEmail + "')].role");
        assertThat(roles).containsExactly("VIEWER");
        assertThat(jmxBody)
                .as("list responses must never contain password material")
                .doesNotContain("password").doesNotContain("$2a$");

        mockMvc.perform(listUsers(companyId("KAM"), session))
                .andExpect(status().isOk());
        assertThat(platformActor).isPositive();
    }

    @Test
    void companyAdminListsOwnCompanyButIsDeniedTheOther() throws Exception {
        companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);

        mockMvc.perform(listUsers(companyId("JMX"), session))
                .andExpect(status().isOk());

        mockMvc.perform(listUsers(companyId("KAM"), session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(listUsers(companyId("JMX"), null))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
        // CSRF is checked before authentication on mutations:
        mockMvc.perform(createUser(companyId("JMX"), null, null,
                        "{\"email\":\"x@example.test\",\"password\":\"irrelevant pw 1\","
                                + "\"fullName\":\"X\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    // ------------------------------------------------------------------
    // Create user + membership
    // ------------------------------------------------------------------

    @Test
    void companyAdminCreatesNewUserWithMembershipAndAtomicAudit() throws Exception {
        long admin = companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        String email = uniqueEmail("created");
        String body = "{\"email\":\"" + email + "\",\"fullName\":\"New Person\","
                + "\"password\":\"brand new password 1\",\"role\":\"FINANCE_OFFICER\"}";

        MvcResult created = mockMvc.perform(createUser(companyId("JMX"), session, token, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.fullName").value("New Person"))
                .andExpect(jsonPath("$.userStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("FINANCE_OFFICER"))
                .andExpect(jsonPath("$.membershipStatus").value("ACTIVE"))
                .andReturn();
        String responseBody = created.getResponse().getContentAsString();
        assertThat(responseBody)
                .as("the response must never expose a password or hash")
                .doesNotContain("password").doesNotContain("$2a$");

        long userId = userIdFrom(responseBody);
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT status, is_platform_admin, password_hash FROM users WHERE id = ?", userId);
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat((Boolean) user.get("is_platform_admin")).isFalse();
        assertThat(passwordEncoder.matches("brand new password 1", (String) user.get("password_hash")))
                .as("the stored hash must verify the given password").isTrue();

        Map<String, Object> membership = jdbc.queryForMap(
                "SELECT id, status, created_by, (SELECT code FROM roles WHERE id = role_id) AS role "
                        + "FROM company_memberships WHERE user_id = ? AND company_id = ?",
                userId, companyId("JMX"));
        assertThat(membership.get("status")).isEqualTo("ACTIVE");
        assertThat(membership.get("role")).isEqualTo("FINANCE_OFFICER");
        assertThat(membership.get("created_by")).isEqualTo(admin);

        // Audit events written atomically with the change, actor = session user.
        long membershipId = ((Number) membership.get("id")).longValue();
        Map<String, Object> userAudit = jdbc.queryForMap(
                "SELECT actor_user_id, company_id, entity_type, entity_id, after_data::text AS data "
                        + "FROM audit_logs WHERE action = 'USER_CREATED' "
                        + "AND after_data->>'email' = ?", email);
        assertThat(userAudit.get("actor_user_id")).isEqualTo(admin);
        assertThat(userAudit.get("company_id")).isEqualTo(companyId("JMX"));
        assertThat(userAudit.get("entity_type")).isEqualTo("USER");
        assertThat(((Number) userAudit.get("entity_id")).longValue()).isEqualTo(userId);
        assertThat((String) userAudit.get("data")).doesNotContain("$2a$")
                .doesNotContain("brand new password 1");

        Map<String, Object> membershipAudit = jdbc.queryForMap(
                "SELECT actor_user_id, company_id, before_data, after_data->>'role' AS role "
                        + "FROM audit_logs WHERE action = 'MEMBERSHIP_CREATED' AND entity_id = ?",
                membershipId);
        assertThat(membershipAudit.get("actor_user_id")).isEqualTo(admin);
        assertThat(membershipAudit.get("company_id")).isEqualTo(companyId("JMX"));
        assertThat(membershipAudit.get("before_data")).isNull();
        assertThat(membershipAudit.get("role")).isEqualTo("FINANCE_OFFICER");
    }

    @Test
    void createdUserCanLogInImmediatelyWithTheGivenPassword() throws Exception {
        companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession adminSession = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(adminSession);

        String email = uniqueEmail("fresh-login");
        String body = "{\"email\":\"" + email + "\",\"fullName\":\"Fresh Login\","
                + "\"password\":\"fresh login password 1\",\"role\":\"VIEWER\"}";
        mockMvc.perform(createUser(companyId("JMX"), adminSession, token, body))
                .andExpect(status().isCreated());

        MockHttpSession freshSession = login(email, "fresh login password 1");
        mockMvc.perform(get("/api/v1/auth/me").session(freshSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.companies.length()").value(1))
                .andExpect(jsonPath("$.companies[0].code").value("JMX"))
                .andExpect(jsonPath("$.companies[0].role").value("VIEWER"));
    }

    @Test
    void sharedRolesAreAllowedAcrossMultipleUsers() throws Exception {
        companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        String first = uniqueEmail("shared-1");
        String second = uniqueEmail("shared-2");
        for (String email : new String[] {first, second}) {
            mockMvc.perform(createUser(companyId("JMX"), session, token,
                            "{\"email\":\"" + email + "\",\"fullName\":\"Shared Role User\","
                                    + "\"password\":\"shared role password 1\","
                                    + "\"role\":\"VIEWER\"}"))
                    .andExpect(status().isCreated());
        }

        String listBody = mockMvc.perform(listUsers(companyId("JMX"), session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> firstRoles = JsonPath.read(listBody,
                "$[?(@.email=='" + first + "')].role");
        List<String> secondRoles = JsonPath.read(listBody,
                "$[?(@.email=='" + second + "')].role");
        assertThat(firstRoles).containsExactly("VIEWER");
        assertThat(secondRoles).containsExactly("VIEWER");
    }

    @Test
    void newAccountPasswordsEnforceTheExistingPasswordPolicy() throws Exception {
        companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Too short (11 chars < 12):
        String shortEmail = uniqueEmail("short-pw");
        mockMvc.perform(createUser(companyId("JMX"), session, token,
                        "{\"email\":\"" + shortEmail + "\",\"fullName\":\"Short PW\","
                                + "\"password\":\"short-pw-1\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, shortEmail)).isZero();

        // 25 CJK characters = 75 UTF-8 bytes: over bcrypt's byte limit even
        // though 25 < 72 characters (byte-based policy, never truncated).
        String cjkEmail = uniqueEmail("cjk-pw");
        String cjkPassword = "\u4f60\u597d".repeat(12) + "\u4f60";
        mockMvc.perform(createUser(companyId("JMX"), session, token,
                        "{\"email\":\"" + cjkEmail + "\",\"fullName\":\"CJK PW\","
                                + "\"password\":\"" + cjkPassword + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, cjkEmail)).isZero();

        // Missing password for a new account:
        String noPwEmail = uniqueEmail("no-pw");
        mockMvc.perform(createUser(companyId("JMX"), session, token,
                        "{\"email\":\"" + noPwEmail + "\",\"fullName\":\"No PW\","
                                + "\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, noPwEmail)).isZero();
    }

    // ------------------------------------------------------------------
    // Adding an existing user: platform admin only, no credential changes
    // ------------------------------------------------------------------

    @Test
    void platformAdminAddsExistingUserToTheOtherCompanyWithoutTouchingCredentials()
            throws Exception {
        String email = uniqueEmail("existing");
        long userId = insertUser(email, "original password 9", "ACTIVE", false);
        addMembership(userId, "JMX", "VIEWER", "ACTIVE");
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT password_hash, full_name, status FROM users WHERE id = ?", userId);
        long membershipCountBefore = jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ?", Long.class, userId);

        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Attach to KAM: no password supplied, name must NOT overwrite theirs.
        mockMvc.perform(createUser(companyId("KAM"), session, token,
                        "{\"email\":\"" + email + "\",\"fullName\":\"Must Be Ignored\","
                                + "\"role\":\"PAYMENT_VERIFIER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("PAYMENT_VERIFIER"))
                .andExpect(jsonPath("$.membershipStatus").value("ACTIVE"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, email)).as("no second user row may be created").isEqualTo(1);
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT password_hash, full_name, status FROM users WHERE id = ?", userId);
        assertThat(after.get("password_hash")).isEqualTo(before.get("password_hash"))
                .as("existing credentials must never change");
        assertThat(after.get("full_name")).isEqualTo(before.get("full_name"))
                .as("existing profile must not be rewritten by an attach");
        assertThat(after.get("status")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ?", Long.class, userId))
                .isEqualTo(membershipCountBefore + 1);

        // Only a MEMBERSHIP_CREATED audit event - no USER_CREATED for this email.
        long kamMembershipId = jdbc.queryForObject(
                "SELECT id FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, userId, companyId("KAM"));
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, company_id FROM audit_logs "
                        + "WHERE action = 'MEMBERSHIP_CREATED' AND entity_id = ?", kamMembershipId);
        assertThat(audit.get("actor_user_id")).isEqualTo(
                jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                        PLATFORM_ACTOR_EMAIL));
        assertThat(audit.get("company_id")).isEqualTo(companyId("KAM"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'USER_CREATED' "
                        + "AND after_data->>'email' = ?", Long.class, email)).isZero();
    }

    @Test
    void companyAdminGetsAGenericConflictForExistingEmailsOutsideTheirCompany()
            throws Exception {
        String email = uniqueEmail("cross-attach");
        long userId = insertUser(email, "cross attach password 1", "ACTIVE", false);
        addMembership(userId, "JMX", "VIEWER", "ACTIVE");

        companyAdmin(COADMIN_KAM_EMAIL, "KAM");
        MockHttpSession session = login(COADMIN_KAM_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Not attached, and the response is the generic 409 conflict - it
        // must NOT confirm that an account exists outside their company
        // (previously a 403 explaining the platform-admin attach flow).
        MvcResult refused = mockMvc.perform(createUser(companyId("KAM"), session, token,
                        "{\"email\":\"" + email + "\",\"fullName\":\"Cross Attach\","
                                + "\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("conflict"))
                .andReturn();
        String body = refused.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("platform")
                .doesNotContainIgnoringCase("attach")
                .doesNotContainIgnoringCase("administrator");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, userId, companyId("KAM"))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, email)).isEqualTo(1);
    }

    @Test
    void creationConflictIsIndistinguishableForCompanyAdmins() throws Exception {
        // Regression: the conflict response for an already-registered email
        // must be byte-identical whether the account is in the admin's OWN
        // company or anywhere else in the system - otherwise the endpoint
        // leaks which emails have accounts outside their company.
        companyAdmin(COADMIN_KAM_EMAIL, "KAM");
        MockHttpSession session = login(COADMIN_KAM_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // (a) account inside the admin's own company:
        String insideEmail = uniqueEmail("conflict-inside");
        long insideId = insertUser(insideEmail, "conflict inside pw 1", "ACTIVE", false);
        addMembership(insideId, "KAM", "VIEWER", "ACTIVE");
        MvcResult inside = mockMvc.perform(createUser(companyId("KAM"), session, token,
                        "{\"email\":\"" + insideEmail + "\",\"fullName\":\"Inside\","
                                + "\"password\":\"whatever password 1\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andReturn();

        // (b) account OUTSIDE their company, probed with a DIFFERENT typing
        // case (also proves email normalization treats both as one identity):
        String outsideEmail = uniqueEmail("conflict-outside");
        long outsideId = insertUser(outsideEmail, "conflict outside pw 1", "ACTIVE", false);
        addMembership(outsideId, "JMX", "VIEWER", "ACTIVE");
        MvcResult outside = mockMvc.perform(createUser(companyId("KAM"), session, token,
                        "{\"email\":\"" + outsideEmail.toUpperCase(java.util.Locale.ROOT) + "\","
                                + "\"fullName\":\"Outside\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(outside.getResponse().getStatus())
                .as("inside: %s / outside: %s", inside.getResponse().getContentAsString(),
                        outside.getResponse().getContentAsString())
                .isEqualTo(inside.getResponse().getStatus());
        assertThat(outside.getResponse().getContentAsString())
                .as("the two conflict responses must be byte-identical")
                .isEqualTo(inside.getResponse().getContentAsString());
        assertThat(inside.getResponse().getContentAsString())
                .contains("\"error\":\"conflict\"")
                .doesNotContainIgnoringCase("platform")
                .doesNotContainIgnoringCase("administrator");

        // Nothing changed anywhere: no membership, no user, no audit event.
        // (KAM holds 2 memberships: the acting company admin + the inside user.)
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE company_id = ?",
                Long.class, companyId("KAM"))).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, insideId, companyId("KAM"))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, outsideId, companyId("KAM"))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, outsideEmail)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE after_data->>'email' IN (?, ?)",
                Long.class, insideEmail, outsideEmail))
                .as("failed create attempts must not write audit events").isZero();
    }

    @Test
    void crossCaseEmailIsTreatedAsTheSameAccountOnAttach() throws Exception {
        // Regression: user creation normalizes emails exactly like login, so
        // a differently-cased variant of a registered email can never create
        // a second identity - it reaches the platform-admin attach path.
        String email = uniqueEmail("cross-case");
        long userId = insertUser(email, "cross case password 1", "ACTIVE", false);
        addMembership(userId, "JMX", "VIEWER", "ACTIVE");

        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        mockMvc.perform(createUser(companyId("KAM"), session, token,
                        "{\"email\":\"  " + email.toUpperCase(java.util.Locale.ROOT) + "  \","
                                + "\"fullName\":\"Must Be Ignored\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = ?",
                Long.class, email)).as("exactly one identity may exist").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, userId, companyId("KAM"))).isEqualTo(1);
    }

    @Test
    void unsupportedMethodsOnKnownEndpointsReturn405Not500() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);
        long jmx = companyId("JMX");
        long targetId = insertUser(uniqueEmail("405-target"), "target password 1", "ACTIVE", false);
        addMembership(targetId, "JMX", "VIEWER", "ACTIVE");

        // DELETE is known-path but unsupported on the collection route.
        MvcResult delete = mockMvc.perform(delete("/api/v1/companies/" + jmx + "/users")
                        .session(session).header("X-XSRF-TOKEN", token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"))
                .andExpect(jsonPath("$.message").value("unsupported method: DELETE"))
                .andReturn();
        assertThat(delete.getResponse().getHeader("Allow")).isEqualTo("GET, POST");

        // PUT is unsupported on the membership route (PATCH only).
        MvcResult put = mockMvc.perform(put("/api/v1/companies/" + jmx + "/users/"
                        + targetId + "/membership")
                        .session(session).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("method_not_allowed"))
                .andReturn();
        assertThat(put.getResponse().getHeader("Allow")).isEqualTo("PATCH");

        // Security checks still run BEFORE method routing: an unsafe method
        // without the CSRF token is a CSRF rejection, not a 405.
        mockMvc.perform(delete("/api/v1/companies/" + jmx + "/users").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        // The refused methods changed nothing:
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ?",
                Long.class, targetId)).isEqualTo(1);
    }

    @Test
    void attachingAnExistingUserNeverAcceptsAPassword() throws Exception {
        String email = uniqueEmail("attach-pw");
        long userId = insertUser(email, "keep this password 1", "ACTIVE", false);
        String originalHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, userId);

        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        mockMvc.perform(createUser(companyId("JMX"), session, token,
                        "{\"email\":\"" + email + "\",\"fullName\":\"Attach PW\","
                                + "\"password\":\"attacker chose pw 1\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?",
                String.class, userId)).isEqualTo(originalHash);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ?", Long.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE after_data->>'email' = ?", Long.class,
                email)).isZero();
    }

    // ------------------------------------------------------------------
    // Duplicates / conflicts
    // ------------------------------------------------------------------

    @Test
    void duplicateMembershipIsRejectedWithConflict() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        String email = uniqueEmail("duplicate");
        String body = "{\"email\":\"" + email + "\",\"fullName\":\"Duplicate User\","
                + "\"password\":\"duplicate password 1\",\"role\":\"VIEWER\"}";
        mockMvc.perform(createUser(companyId("JMX"), session, token, body))
                .andExpect(status().isCreated());

        // Replaying the CREATE body (with password) for the now-existing user
        // must be refused: attachments never carry credentials (400).
        mockMvc.perform(createUser(companyId("JMX"), session, token, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("password must not be provided when adding an existing user"));

        // A password-free attach for the same (company, user): 409, one row stays.
        String attachBody = "{\"email\":\"" + email + "\",\"role\":\"VIEWER\"}";
        MvcResult duplicate = mockMvc.perform(
                        createUser(companyId("JMX"), session, token, attachBody))
                .andReturn();
        assertThat(duplicate.getResponse().getStatus())
                .as("second response body: %s", duplicate.getResponse().getContentAsString())
                .isEqualTo(409);
        assertThat(duplicate.getResponse().getContentAsString()).contains("conflict");

        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, userId, companyId("JMX"))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'USER_CREATED' "
                        + "AND after_data->>'email' = ?", Long.class, email))
                .as("the failed attempt must not add audit events").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Role change / activate / deactivate - immediate effects
    // ------------------------------------------------------------------

    @Test
    void roleChangeTakesEffectImmediatelyInTheTargetUsersSession() throws Exception {
        long admin = companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession adminSession = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(adminSession);

        String email = uniqueEmail("role-effect");
        String body = "{\"email\":\"" + email + "\",\"fullName\":\"Role Effect\","
                + "\"password\":\"role effect password 1\",\"role\":\"VIEWER\"}";
        long userId = userIdFrom(
                mockMvc.perform(createUser(companyId("JMX"), adminSession, token, body))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());

        MockHttpSession targetSession = login(email, "role effect password 1");
        mockMvc.perform(get("/api/v1/auth/me").session(targetSession))
                .andExpect(jsonPath("$.companies[0].role").value("VIEWER"));

        mockMvc.perform(patchMembership(companyId("JMX"), userId, adminSession, token,
                        "{\"role\":\"FINANCE_OFFICER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("FINANCE_OFFICER"));

        // The SAME session sees the new role - no re-login (fresh DB read).
        mockMvc.perform(get("/api/v1/auth/me").session(targetSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].role").value("FINANCE_OFFICER"));
        assertThat(accessControlService.permissionsFor(userId, companyId("JMX")))
                .as("permissions must reflect the new role immediately")
                .contains("PAYMENT_RECORD");

        // Idempotent repeat: no second audit event for an unchanged role.
        long membershipId = jdbc.queryForObject(
                "SELECT id FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, userId, companyId("JMX"));
        mockMvc.perform(patchMembership(companyId("JMX"), userId, adminSession, token,
                        "{\"role\":\"FINANCE_OFFICER\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'MEMBERSHIP_ROLE_CHANGED' "
                        + "AND entity_id = ?", Long.class, membershipId)).isEqualTo(1);

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, before_data->>'role' AS old_role, "
                        + "after_data->>'role' AS new_role FROM audit_logs "
                        + "WHERE action = 'MEMBERSHIP_ROLE_CHANGED' AND entity_id = ?",
                membershipId);
        assertThat(audit.get("actor_user_id")).isEqualTo(admin)
                .as("the actor must come from the acting session, not the target");
        assertThat(audit.get("old_role")).isEqualTo("VIEWER");
        assertThat(audit.get("new_role")).isEqualTo("FINANCE_OFFICER");
    }

    @Test
    void deactivatingAndReactivatingAMembershipTakesEffectImmediately() throws Exception {
        long admin = companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession adminSession = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(adminSession);

        String email = uniqueEmail("deactivate");
        long userId = userIdFrom(
                mockMvc.perform(createUser(companyId("JMX"), adminSession, token,
                                "{\"email\":\"" + email + "\",\"fullName\":\"Deactivate Me\","
                                        + "\"password\":\"deactivate password 1\","
                                        + "\"role\":\"FINANCE_OFFICER\"}"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());
        MockHttpSession targetSession = login(email, "deactivate password 1");
        mockMvc.perform(get("/api/v1/auth/me").session(targetSession))
                .andExpect(jsonPath("$.companies.length()").value(1));

        mockMvc.perform(patchMembership(companyId("JMX"), userId, adminSession, token,
                        "{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipStatus").value("INACTIVE"));

        // An inactive membership grants nothing, immediately:
        mockMvc.perform(get("/api/v1/auth/me").session(targetSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies.length()").value(0));
        assertThat(accessControlService.permissionsFor(userId, companyId("JMX")))
                .as("permissions must be gone immediately")
                .isEmpty();

        mockMvc.perform(patchMembership(companyId("JMX"), userId, adminSession, token,
                        "{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.membershipStatus").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/auth/me").session(targetSession))
                .andExpect(jsonPath("$.companies.length()").value(1))
                .andExpect(jsonPath("$.companies[0].role").value("FINANCE_OFFICER"));

        long membershipId = jdbc.queryForObject(
                "SELECT id FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, userId, companyId("JMX"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'MEMBERSHIP_STATUS_CHANGED' "
                        + "AND entity_id = ?", Long.class, membershipId))
                .as("one audit event per actual change").isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Cross-company denial and privilege escalation
    // ------------------------------------------------------------------

    @Test
    void companyAdminCannotChangeMembershipsInTheOtherCompany() throws Exception {
        String email = uniqueEmail("kam-member");
        long userId = insertUser(email, "kam member password 1", "ACTIVE", false);
        addMembership(userId, "KAM", "VIEWER", "ACTIVE");

        companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Addressing the OTHER company's path is refused before any lookup:
        mockMvc.perform(patchMembership(companyId("KAM"), userId, session, token,
                        "{\"role\":\"COMPANY_ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mockMvc.perform(createUser(companyId("KAM"), session, token,
                        "{\"email\":\"" + uniqueEmail("sneaky") + "\","
                                + "\"fullName\":\"Sneaky\",\"password\":\"sneaky password 1\","
                                + "\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(listUsers(companyId("KAM"), session))
                .andExpect(status().isForbidden());

        String roleAfter = jdbc.queryForObject(
                "SELECT r.code FROM company_memberships m JOIN roles r ON r.id = m.role_id "
                        + "WHERE m.user_id = ? AND m.company_id = ?",
                String.class, userId, companyId("KAM"));
        assertThat(roleAfter).as("the KAM membership must be untouched").isEqualTo("VIEWER");
    }

    @Test
    void membershipOutsideTheAddressedCompanyIsNotFound() throws Exception {
        // A KAM-only user addressed through the JMX path: 404 (the same
        // response as a nonexistent user - no cross-company disclosure).
        String email = uniqueEmail("not-in-jmx");
        long userId = insertUser(email, "not in jmx password 1", "ACTIVE", false);
        addMembership(userId, "KAM", "VIEWER", "ACTIVE");

        companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        mockMvc.perform(patchMembership(companyId("JMX"), userId, session, token,
                        "{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM company_memberships WHERE user_id = ? AND company_id = ?",
                String.class, userId, companyId("KAM"))).isEqualTo("ACTIVE");
    }

    @Test
    void thePlatformAdminRoleCannotBeAssignedThroughTheApi() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Escalation attempt on CREATE:
        String email = uniqueEmail("escalate-create");
        mockMvc.perform(createUser(companyId("JMX"), session, token,
                        "{\"email\":\"" + email + "\",\"fullName\":\"Escalate\","
                                + "\"password\":\"escalation password 1\","
                                + "\"role\":\"PLATFORM_ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, email)).isZero();

        // Escalation attempt on an existing membership:
        long userId = insertUser(uniqueEmail("escalate-patch"), "escalate patch pw 1",
                "ACTIVE", false);
        addMembership(userId, "JMX", "VIEWER", "ACTIVE");
        mockMvc.perform(patchMembership(companyId("JMX"), userId, session, token,
                        "{\"role\":\"PLATFORM_ADMIN\"}"))
                .andExpect(status().isForbidden());
        String roleAfter = jdbc.queryForObject(
                "SELECT r.code FROM company_memberships m JOIN roles r ON r.id = m.role_id "
                        + "WHERE m.user_id = ? AND m.company_id = ?",
                String.class, userId, companyId("JMX"));
        assertThat(roleAfter).isEqualTo("VIEWER");

        // The flag itself is untouched everywhere:
        assertThat(jdbc.queryForObject("SELECT is_platform_admin FROM users WHERE id = ?",
                Boolean.class, userId)).isFalse();
    }

    @Test
    void requestFieldsCannotSetThePlatformAdminFlagOrGlobalAccountData()
            throws Exception {
        companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Unknown JSON fields are ignored (Jackson 3 default): the flag can
        // neither be requested at creation...
        String email = uniqueEmail("extra-fields");
        long userId = userIdFrom(
                mockMvc.perform(createUser(companyId("JMX"), session, token,
                                "{\"email\":\"" + email + "\",\"fullName\":\"Original Name\","
                                        + "\"password\":\"extra fields password 1\","
                                        + "\"role\":\"VIEWER\","
                                        + "\"isPlatformAdmin\":true,\"platformAdmin\":true}"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT is_platform_admin FROM users WHERE id = ?",
                Boolean.class, userId)).isFalse();

        // ...nor smuggled into a membership PATCH (global status/profile of
        // an existing user is not mutable through this API at all).
        mockMvc.perform(patchMembership(companyId("JMX"), userId, session, token,
                        "{\"role\":\"FINANCE_OFFICER\",\"userStatus\":\"DISABLED\","
                                + "\"fullName\":\"Hacked Name\",\"isPlatformAdmin\":true,"
                                + "\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("FINANCE_OFFICER"));

        Map<String, Object> user = jdbc.queryForMap(
                "SELECT full_name, status, is_platform_admin FROM users WHERE id = ?", userId);
        assertThat(user.get("full_name")).isEqualTo("Original Name");
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat((Boolean) user.get("is_platform_admin")).isFalse();
    }

    @Test
    void userWithoutUserManageIsDeniedOnEveryManagementEndpoint() throws Exception {
        String officerEmail = uniqueEmail("no-manage");
        long officerId = insertUser(officerEmail, "no manage password 1", "ACTIVE", false);
        addMembership(officerId, "JMX", "FINANCE_OFFICER", "ACTIVE");
        MockHttpSession session = login(officerEmail, "no manage password 1");
        String token = csrfToken(session);

        mockMvc.perform(listUsers(companyId("JMX"), session))
                .andExpect(status().isForbidden());
        mockMvc.perform(createUser(companyId("JMX"), session, token,
                        "{\"email\":\"" + uniqueEmail("denied-create") + "\","
                                + "\"fullName\":\"Denied\",\"password\":\"denied password 1\","
                                + "\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
        long targetId = insertUser(uniqueEmail("denied-target"), "denied target pw 1",
                "ACTIVE", false);
        addMembership(targetId, "JMX", "VIEWER", "ACTIVE");
        mockMvc.perform(patchMembership(companyId("JMX"), targetId, session, token,
                        "{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // CSRF, validation, unknown scope
    // ------------------------------------------------------------------

    @Test
    void mutationsRequireTheCsrfToken() throws Exception {
        companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);

        // Authenticated but no token: refused.
        mockMvc.perform(post("/api/v1/companies/" + companyId("JMX") + "/users")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail("no-csrf") + "\","
                                + "\"fullName\":\"No CSRF\",\"password\":\"no csrf password 1\","
                                + "\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));

        // GET (safe method) needs no token; mutations with a token succeed.
        mockMvc.perform(listUsers(companyId("JMX"), session))
                .andExpect(status().isOk());
        String token = csrfToken(session);
        mockMvc.perform(createUser(companyId("JMX"), session, token,
                        "{\"email\":\"" + uniqueEmail("with-csrf") + "\","
                                + "\"fullName\":\"With CSRF\",\"password\":\"with csrf pw 1\","
                                + "\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidInputIsRejectedWithBadRequest() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        // Unknown role:
        String unknownRoleEmail = uniqueEmail("bad-role");
        mockMvc.perform(createUser(companyId("JMX"), session, token,
                        "{\"email\":\"" + unknownRoleEmail + "\",\"fullName\":\"Bad Role\","
                                + "\"password\":\"bad role password 1\","
                                + "\"role\":\"NO_SUCH_ROLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("unknown role: NO_SUCH_ROLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, unknownRoleEmail)).isZero();

        // Empty PATCH body:
        long userId = insertUser(uniqueEmail("empty-patch"), "empty patch pw 1", "ACTIVE", false);
        addMembership(userId, "JMX", "VIEWER", "ACTIVE");
        mockMvc.perform(patchMembership(companyId("JMX"), userId, session, token, "{}"))
                .andExpect(status().isBadRequest());

        // Invalid membership status:
        mockMvc.perform(patchMembership(companyId("JMX"), userId, session, token,
                        "{\"status\":\"BANANA\"}"))
                .andExpect(status().isBadRequest());

        // Unknown company id fails closed (403, not data):
        mockMvc.perform(listUsers(999_999_999L, session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void roleAndStatusCanBeChangedTogetherInOneRequest() throws Exception {
        long admin = companyAdmin(COADMIN_JMX_EMAIL, "JMX");
        MockHttpSession session = login(COADMIN_JMX_EMAIL, ACTOR_PASSWORD);
        String token = csrfToken(session);

        String email = uniqueEmail("combined");
        long userId = userIdFrom(
                mockMvc.perform(createUser(companyId("JMX"), session, token,
                                "{\"email\":\"" + email + "\",\"fullName\":\"Combined Change\","
                                        + "\"password\":\"combined password 1\","
                                        + "\"role\":\"VIEWER\"}"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());

        mockMvc.perform(patchMembership(companyId("JMX"), userId, session, token,
                        "{\"role\":\"PAYMENT_VERIFIER\",\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PAYMENT_VERIFIER"))
                .andExpect(jsonPath("$.membershipStatus").value("INACTIVE"));

        long membershipId = jdbc.queryForObject(
                "SELECT id FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Long.class, userId, companyId("JMX"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action IN "
                        + "('MEMBERSHIP_ROLE_CHANGED', 'MEMBERSHIP_STATUS_CHANGED') "
                        + "AND entity_id = ?", Long.class, membershipId))
                .as("one audit event per changed attribute").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT actor_user_id FROM audit_logs WHERE action = 'MEMBERSHIP_STATUS_CHANGED' "
                        + "AND entity_id = ?", Long.class, membershipId)).isEqualTo(admin);
    }

    @Test
    void nonNumericPathParametersAreBadRequest() throws Exception {
        ensureFixtureUser(PLATFORM_ACTOR_EMAIL, true);
        MockHttpSession session = login(PLATFORM_ACTOR_EMAIL, ACTOR_PASSWORD);

        mockMvc.perform(get("/api/v1/companies/abc/users").session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("invalid path parameter: companyId"));
        String token = csrfToken(session);
        mockMvc.perform(patch("/api/v1/companies/1/users/xyz/membership")
                        .session(session)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("invalid path parameter: userId"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** JsonPath returns Integer for JSON numbers; convert safely. */
    private static long userIdFrom(String responseBody) {
        return ((Number) JsonPath.read(responseBody, "$.userId")).longValue();
    }

    private MockHttpSession login(String email, String password) throws Exception {        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private MockHttpServletRequestBuilder listUsers(long companyId, MockHttpSession session) {
        MockHttpServletRequestBuilder request = get("/api/v1/companies/" + companyId + "/users");
        if (session != null) {
            request.session(session);
        }
        return request;
    }

    private MockHttpServletRequestBuilder createUser(long companyId, MockHttpSession session,
            String csrfToken, String json) {
        MockHttpServletRequestBuilder request = post(
                        "/api/v1/companies/" + companyId + "/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (session != null) {
            request.session(session);
        }
        if (csrfToken != null) {
            request.header("X-XSRF-TOKEN", csrfToken);
        }
        return request;
    }

    private MockHttpServletRequestBuilder patchMembership(long companyId, long userId,
            MockHttpSession session, String csrfToken, String json) {
        MockHttpServletRequestBuilder request = patch(
                        "/api/v1/companies/" + companyId + "/users/" + userId + "/membership")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (session != null) {
            request.session(session);
        }
        if (csrfToken != null) {
            request.header("X-XSRF-TOKEN", csrfToken);
        }
        return request;
    }

    /**
     * Creates or resets a fixture actor. Fixture users survive cleanup (they
     * are audit actors), so each run re-promotes them to the state the test
     * needs instead of inserting a new row.
     */
    private long ensureFixtureUser(String email, boolean platformAdmin) {
        Long existing = jdbc.query("SELECT id FROM users WHERE email = ?",
                (rs, rowNum) -> rs.getLong(1), email).stream().findFirst().orElse(null);
        if (existing == null) {
            return insertUser(email, ACTOR_PASSWORD, "ACTIVE", platformAdmin);
        }
        jdbc.update("""
                UPDATE users
                SET password_hash = ?, status = 'ACTIVE', is_platform_admin = ?,
                    full_name = 'Fixture Actor', updated_at = now()
                WHERE id = ?
                """, passwordEncoder.encode(ACTOR_PASSWORD), platformAdmin, existing);
        return existing;
    }

    private long companyAdmin(String email, String companyCode) {
        long actorId = ensureFixtureUser(email, false);
        Integer memberships = jdbc.queryForObject(
                "SELECT count(*) FROM company_memberships WHERE user_id = ? AND company_id = ?",
                Integer.class, actorId, companyId(companyCode));
        if (memberships == null || memberships == 0) {
            addMembership(actorId, companyCode, "COMPANY_ADMIN", "ACTIVE");
        }
        return actorId;
    }
}
