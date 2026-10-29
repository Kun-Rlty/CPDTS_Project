package com.cpdts.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.cpdts.service.AccessControlService;

/**
 * Base class for authentication-foundation integration tests.
 *
 * <p>Every test runs against the REAL test database ({@code cpdts_test}) with
 * the REAL security filter chain (MockMvc built with {@code springSecurity()}).
 *
 * <p>Data-ownership rule: tests only create rows in the reserved
 * {@code @example.test} email domain and clean them up
 * ({@link #deleteAllTestUsers()}); seeded reference data (companies, roles,
 * permissions) is never modified.
 */
@SpringBootTest
public abstract class CpdtsIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected AccessControlService accessControlService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    protected MockMvc mockMvc;

    @BeforeEach
    void verifyTestDatabaseAndBuildMockMvc() {
        // Hard safety guard: refuse to run if the URL override ever fails.
        String database = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(database).isEqualTo("cpdts_test");

        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    protected long companyId(String code) {
        return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
    }

    /** Inserts a test user with a real BCrypt hash; returns the new id. */
    protected long insertUser(String email, String rawPassword, String status,
            boolean platformAdmin) {
        jdbc.update("""
                INSERT INTO users (email, password_hash, full_name, status, is_platform_admin)
                VALUES (?, ?, 'Test User', ?, ?)
                """, email, passwordEncoder.encode(rawPassword), status, platformAdmin);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    protected void setUserStatus(long userId, String status) {
        jdbc.update("UPDATE users SET status = ?, updated_at = now() WHERE id = ?", status, userId);
    }

    protected void addMembership(long userId, String companyCode, String roleCode, String status) {
        jdbc.update("""
                INSERT INTO company_memberships (company_id, user_id, role_id, status)
                SELECT c.id, ?, r.id, ?
                FROM companies c, roles r
                WHERE c.code = ? AND r.code = ?
                """, userId, status, companyCode, roleCode);
    }

    /** Removes one test user and its memberships (tests own their rows). */
    protected void deleteUser(long userId) {
        jdbc.update("DELETE FROM company_memberships WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    /** All test-created users use the reserved {@code @example.test} domain. */
    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.test";
    }

    /** Bulk cleanup of every row the test suite created (scratch test DB). */
    protected void deleteAllTestUsers() {
        jdbc.update("""
                DELETE FROM company_memberships
                WHERE user_id IN (SELECT id FROM users WHERE email LIKE '%@example.test')
                """);
        jdbc.update("DELETE FROM users WHERE email LIKE '%@example.test'");
    }
}
