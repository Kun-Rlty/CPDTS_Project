package com.cpdts.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cpdts.security.PasswordPolicy;
import com.cpdts.support.CpdtsIntegrationTest;

/**
 * Tests for the one-time platform-admin bootstrap logic (the same code the
 * {@code PlatformAdminBootstrap} main command runs) against the real test
 * database.
 */
class PlatformAdminBootstrapperTest extends CpdtsIntegrationTest {

    private static final String ADMIN_PASSWORD = "initial admin password 1";

    @BeforeEach
    void ensureNoPlatformAdminExists() {
        // Deterministic state on the scratch test DB: tests own all their rows.
        deleteAllTestUsers();
        jdbc.update(
                "DELETE FROM company_memberships WHERE user_id IN "
                        + "(SELECT id FROM users WHERE is_platform_admin)");
        jdbc.update("DELETE FROM users WHERE is_platform_admin");
    }

    @AfterEach
    void cleanUpTestUsers() {
        deleteAllTestUsers();
    }

    @Test
    void createsTheInitialPlatformAdminStoringOnlyABcryptHashAndAnAuditEvent() {
        String email = uniqueEmail("bootstrap-ok");

        String created = bootstrapper().createInitialPlatformAdmin(email, "Bootstrap Admin",
                ADMIN_PASSWORD);

        assertThat(created).isEqualTo(email);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, email, password_hash, full_name, status, is_platform_admin "
                        + "FROM users WHERE email = ?", email);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat((Boolean) row.get("is_platform_admin")).isTrue();

        String storedHash = (String) row.get("password_hash");
        assertThat(storedHash).as("raw password must never be stored")
                .doesNotContain(ADMIN_PASSWORD);
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, storedHash))
                .as("stored hash must verify the password").isTrue();

        // The creation wrote an audit event (PRD FR-09) with identity fields
        // only - never the password or its hash. Fields are extracted via jsonb
        // operators so the assertion is independent of JSON key order/format.
        long userId = ((Number) row.get("id")).longValue();
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT actor_user_id, company_id, entity_type, entity_id, reason, request_id, "
                        + "after_data->>'email' AS audit_email, "
                        + "after_data->>'full_name' AS audit_full_name, "
                        + "after_data->>'status' AS audit_status, "
                        + "(after_data->>'is_platform_admin')::boolean AS audit_platform_admin, "
                        + "after_data::text AS after_data "
                        + "FROM audit_logs "
                        + "WHERE action = 'PLATFORM_ADMIN_BOOTSTRAPPED' AND entity_id = ?", userId);
        assertThat(audit.get("actor_user_id"))
                .as("system-generated event has no user actor").isNull();
        assertThat(audit.get("company_id"))
                .as("platform-wide event has no company scope").isNull();
        assertThat(audit.get("entity_type")).isEqualTo("USER");
        assertThat(audit.get("reason")).isEqualTo("one-time platform administrator bootstrap");
        assertThat(audit.get("audit_email")).isEqualTo(email);
        assertThat(audit.get("audit_full_name")).isEqualTo("Bootstrap Admin");
        assertThat(audit.get("audit_status")).isEqualTo("ACTIVE");
        assertThat((Boolean) audit.get("audit_platform_admin")).isTrue();
        String afterData = (String) audit.get("after_data");
        assertThat(afterData).as("audit must never contain the password or its hash")
                .doesNotContain(ADMIN_PASSWORD).doesNotContain(storedHash).doesNotContain("$2");
    }

    @Test
    void normalizesTheEmailToTrimmedLowercaseBeforeStoring() {
        // Regression: bootstrap, login and user creation all normalize
        // through the shared EmailNormalizer, so a mixed-case entry with
        // surrounding whitespace becomes the same canonical identity
        // everywhere (and no second account can diverge by typing case).
        String created = bootstrapper().createInitialPlatformAdmin(
                "  Mixed.Case.Admin@Example.TEST  ", "Mixed Case Admin", ADMIN_PASSWORD);

        String expected = "mixed.case.admin@example.test";
        assertThat(created).isEqualTo(expected);
        assertThat(jdbc.queryForObject(
                "SELECT email FROM users WHERE email = ?", String.class, expected))
                .isEqualTo(expected);
    }

    @Test
    void adminRecordAndAuditEventShareOneTransaction() throws Exception {
        String email = uniqueEmail("bootstrap-atomic");

        // A connection whose commit() fails: both inserts must roll back
        // together - the privileged record can never exist without its audit
        // event, nor the audit event without the record.
        Connection real = dataSource.getConnection();
        Connection sabotaged = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("commit".equals(method.getName())) {
                        throw new SQLException("simulated commit failure");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        PlatformAdminBootstrapper atomic = new PlatformAdminBootstrapper(() -> sabotaged,
                passwordEncoder);

        assertThatThrownBy(() -> atomic.createInitialPlatformAdmin(email, "Atomic Admin",
                        ADMIN_PASSWORD))
                .isInstanceOf(BootstrapException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, email)).as("no user row may survive the failed commit").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'PLATFORM_ADMIN_BOOTSTRAPPED' "
                        + "AND after_data->>'email' = ?", Long.class, email))
                .as("no audit row may survive the failed commit").isZero();
        real.close();
    }

    @Test
    void acceptsAMultibytePasswordWithinThe72ByteBcryptLimit() {
        // 24 CJK characters = exactly 72 UTF-8 bytes: at the bcrypt limit.
        String multibytePassword = "\u4f60\u597d".repeat(12);
        assertThat(multibytePassword.getBytes(StandardCharsets.UTF_8).length)
                .as("test fixture must be exactly 72 UTF-8 bytes").isEqualTo(72);

        String email = uniqueEmail("bootstrap-cjk");
        bootstrapper().createInitialPlatformAdmin(email, "Multibyte Admin", multibytePassword);

        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(passwordEncoder.matches(multibytePassword, storedHash)).isTrue();
    }

    @Test
    void refusesAMultibytePasswordBeyondThe72ByteBcryptLimit() {
        // 25 CJK characters = 75 UTF-8 bytes: over the byte limit (but only
        // 25 CHARACTERS - a char-based check would wrongly accept it).
        String overlongPassword = "\u4f60\u597d".repeat(12) + "\u4f60";
        assertThat(overlongPassword.getBytes(StandardCharsets.UTF_8).length).isEqualTo(75);

        String email = uniqueEmail("bootstrap-cjk-long");
        assertThatThrownBy(() -> bootstrapper().createInitialPlatformAdmin(email,
                        "Multibyte Admin", overlongPassword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-8 bytes");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?",
                Long.class, email)).isZero();
    }

    @Test
    void refusesToCreateASecondInitialPlatformAdmin() {
        bootstrapper().createInitialPlatformAdmin(uniqueEmail("bootstrap-first"), "First Admin",
                ADMIN_PASSWORD);
        long admins = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE is_platform_admin", Long.class);
        assertThat(admins).isEqualTo(1);

        assertThatThrownBy(() -> bootstrapper().createInitialPlatformAdmin(
                        uniqueEmail("bootstrap-second"), "Second Admin",
                        "another admin password 2"))
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("already exists");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE is_platform_admin",
                Long.class)).isEqualTo(1);
    }

    @Test
    void rejectsPasswordsBelowTheProvisionalPolicyWithoutCreatingAnything() {
        String email = uniqueEmail("bootstrap-weak");

        assertThatThrownBy(() -> bootstrapper().createInitialPlatformAdmin(email, "Weak Admin",
                        "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Long.class, email)).isZero();
    }

    @Test
    void refusesWhenTheDatabaseSchemaIsNotTheExpectedVersion() {
        assertThatThrownBy(() -> bootstrapper().createInitialPlatformAdmin(
                        uniqueEmail("bootstrap-ver"), "Admin", ADMIN_PASSWORD, 999))
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("expects");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email LIKE 'bootstrap-ver%'", Long.class))
                .isZero();
    }

    @Test
    void refusesWhileAnotherBootstrapRunHoldsTheAdvisoryLock() throws Exception {
        try (Connection blocker = dataSource.getConnection();
                Statement statement = blocker.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + PlatformAdminBootstrapper.BOOTSTRAP_LOCK_KEY
                    + ")");
            try {
                assertThatThrownBy(() -> bootstrapper().createInitialPlatformAdmin(
                                uniqueEmail("bootstrap-lock"), "Admin", ADMIN_PASSWORD))
                        .isInstanceOf(BootstrapException.class)
                        .hasMessageContaining("another bootstrap");
            } finally {
                statement.execute(
                        "SELECT pg_advisory_unlock(" + PlatformAdminBootstrapper.BOOTSTRAP_LOCK_KEY
                                + ")");
            }
        }
    }

    @Test
    void passwordPolicyAllowsExactlyTheProvisionalRange() {
        assertThatNoException().isThrownBy(() -> PasswordPolicy.validate("a".repeat(12)));
        assertThatNoException().isThrownBy(() -> PasswordPolicy.validate("a".repeat(72)));
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("a".repeat(73)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PlatformAdminBootstrapper bootstrapper() {
        return new PlatformAdminBootstrapper(() -> {
            try {
                return dataSource.getConnection();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, passwordEncoder);
    }
}
