package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cpdts.support.CpdtsIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * API-level tests for /api/v1/auth/*: login, logout, session flow, CSRF,
 * inactive users, and non-enumeration of credentials errors.
 */
class AuthEndpointTest extends CpdtsIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";

    @AfterEach
    void cleanUpTestUsers() {
        deleteAllTestUsers();
    }

    @Test
    void loginWithValidCredentialsReturnsSessionUserAndCompanies() throws Exception {
        String email = uniqueEmail("login-ok");
        long userId = insertUser(email, PASSWORD, "ACTIVE", false);
        addMembership(userId, "JMX", "FINANCE_OFFICER", "ACTIVE");

        MvcResult login = mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.platformAdmin").value(false))
                .andExpect(jsonPath("$.companies.length()").value(1))
                .andExpect(jsonPath("$.companies[0].code").value("JMX"))
                .andExpect(jsonPath("$.companies[0].role").value("FINANCE_OFFICER"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).as("login must create an HTTP session").isNotNull();
        // Note: MockMvc does not emulate container cookies (JSESSIONID is
        // written by Tomcat in production, not by the mock response).

        // The session authenticates later requests.
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId));
        mockMvc.perform(get("/api/v1/auth/companies").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("JMX"));

        // last_login_at was recorded server-side.
        assertThat(jdbc.queryForObject(
                "SELECT last_login_at IS NOT NULL FROM users WHERE id = ?", Boolean.class,
                userId)).isTrue();
    }

    @Test
    void loginWithWrongPasswordIsRejectedWithGenericError() throws Exception {
        String email = uniqueEmail("login-badpw");
        insertUser(email, PASSWORD, "ACTIVE", false);

        mockMvc.perform(login(email, "this-is-not-the-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void unknownEmailAndWrongPasswordProduceTheIdenticalError() throws Exception {
        // Unknown email:
        String unknownBody = mockMvc.perform(
                        login(uniqueEmail("ghost"), "whatever-password-1"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Real user, wrong password:
        String email = uniqueEmail("login-real");
        insertUser(email, PASSWORD, "ACTIVE", false);
        String wrongPasswordBody = mockMvc.perform(login(email, "this-is-not-the-password"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Identical responses: an attacker cannot tell accounts apart.
        assertThat(unknownBody).isEqualTo(wrongPasswordBody);
    }

    @Test
    void disabledAccountIsRejectedOnlyAfterCorrectPassword() throws Exception {
        String email = uniqueEmail("login-disabled");
        insertUser(email, PASSWORD, "DISABLED", false);

        // Correct password -> explicit "disabled" (only for someone who knows it).
        mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("account_disabled"));

        // Wrong password -> the same generic error as for active accounts.
        mockMvc.perform(login(email, "this-is-not-the-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        String email = uniqueEmail("login-csrfguard");
        insertUser(email, PASSWORD, "ACTIVE", false);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void csrfEndpointIssuesATokenThatWorksForLogin() throws Exception {
        MvcResult csrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        // Session-based CSRF: the token lives in the HTTP session created by
        // this first request; the client echoes the JSON token in the header.
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
        assertThat(session).as("the /csrf call must create a session").isNotNull();
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");

        String email = uniqueEmail("login-csrfok");
        insertUser(email, PASSWORD, "ACTIVE", false);

        // Same session + X-XSRF-TOKEN header: no csrf() helper involved.
        mockMvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        String email = uniqueEmail("login-logout");
        insertUser(email, PASSWORD, "ACTIVE", false);

        MvcResult login = mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        assertThat(session.isInvalid()).as("session must be invalidated by logout").isTrue();
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatingAUserEndsTheirExistingSessionOnTheNextRequest() throws Exception {
        String email = uniqueEmail("login-deactivate");
        long userId = insertUser(email, PASSWORD, "ACTIVE", false);

        MvcResult login = mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        // Still fine before deactivation:
        mockMvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());

        // Deactivate server-side (e.g. by a company admin in a later phase):
        setUserStatus(userId, "DISABLED");

        // The very next request with the SAME session is rejected.
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRotatesTheSessionIdOfAPreLoginSession() throws Exception {
        // A pre-login session, e.g. one created by the CSRF endpoint:
        MvcResult csrf = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession preLoginSession = (MockHttpSession) csrf.getRequest().getSession(false);
        assertThat(preLoginSession).as("the /csrf call creates a pre-login session").isNotNull();
        String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
        String preLoginId = preLoginSession.getId();

        String email = uniqueEmail("login-rotate");
        long userId = insertUser(email, PASSWORD, "ACTIVE", false);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .session(preLoginSession)
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession afterLogin = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(afterLogin).isNotNull();
        assertThat(afterLogin.getId())
                .as("login must rotate the session ID (session-fixation protection)")
                .isNotEqualTo(preLoginId);

        // The rotated session carries the authentication:
        mockMvc.perform(get("/api/v1/auth/me").session(afterLogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId));
    }

    @Test
    void multibytePasswordWithinTheBcryptByteLimitCanLogIn() throws Exception {
        // 24 CJK characters = exactly 72 UTF-8 bytes: at the bcrypt limit.
        String password = "\u4f60\u597d".repeat(12);
        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .as("test fixture must be exactly 72 UTF-8 bytes").isEqualTo(72);
        String email = uniqueEmail("login-cjk");
        insertUser(email, password, "ACTIVE", false);

        mockMvc.perform(login(email, password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void passwordBeyondTheBcryptByteLimitIsRejectedNotTruncated() throws Exception {
        String email = uniqueEmail("login-long");
        String realPassword = "a".repeat(72);
        insertUser(email, realPassword, "ACTIVE", false);

        // Truncation-equivalence attempt: a longer password sharing the first
        // 72 bytes with the real one must NOT authenticate (bcrypt never
        // truncates silently and the API rejects over-long input up front).
        String extended = realPassword + "XYZ-extra-tail";
        mockMvc.perform(login(email, extended))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        // Multibyte over-long input is rejected the same way: 25 CJK chars =
        // 75 UTF-8 bytes even though 25 < 72 characters.
        String cjkOverlong = "\u4f60\u597d".repeat(12) + "\u4f60";
        assertThat(cjkOverlong.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isEqualTo(75);
        mockMvc.perform(login(email, cjkOverlong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        // The real password still works afterwards:
        mockMvc.perform(login(email, realPassword)).andExpect(status().isOk());
    }

    @Test
    void membershipRoleChangesAreHonoredInTheLiveSession() throws Exception {
        String email = uniqueEmail("login-rolechange");
        long userId = insertUser(email, PASSWORD, "ACTIVE", false);
        addMembership(userId, "JMX", "FINANCE_OFFICER", "ACTIVE");

        MvcResult login = mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].role").value("FINANCE_OFFICER"));

        // An admin reassigns the role mid-session (one role per membership):
        jdbc.update("UPDATE company_memberships "
                        + "SET role_id = (SELECT id FROM roles WHERE code = 'VIEWER'), "
                        + "updated_at = now() "
                        + "WHERE user_id = ? AND company_id = ?", userId, companyId("JMX"));

        // The SAME session reflects the new role immediately - no re-login.
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].role").value("VIEWER"));

        // Deactivating the membership removes company access entirely:
        jdbc.update("UPDATE company_memberships "
                        + "SET status = 'INACTIVE', updated_at = now() "
                        + "WHERE user_id = ? AND company_id = ?", userId, companyId("JMX"));
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies.length()").value(0));
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthenticated"));
        mockMvc.perform(get("/api/v1/auth/companies"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()))
                .andExpect(status().isOk()); // logout of an anonymous call is a safe no-op
    }

    private static MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }
}
