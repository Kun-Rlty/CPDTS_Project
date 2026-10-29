package com.cpdts.bootstrap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.cpdts.security.PasswordPolicy;

/**
 * Creates the FIRST and ONLY initial platform administrator.
 *
 * <p>Safety rails (all fail closed):
 * <ul>
 *   <li>Schema guard: refuses to run unless Flyway reports exactly the schema
 *       version this build expects (avoids running against an unmigrated or
 *       future database).</li>
 *   <li>Advisory lock: PostgreSQL session-level lock so two concurrent
 *       bootstrap runs cannot both pass the existence check.</li>
 *   <li>One-time rule: refuses if ANY user with is_platform_admin already
 *       exists - a second initial platform admin can never be created by
 *       accident. Later administrators are created by an approved
 *       user-management phase, not by this command.</li>
 *   <li>Atomic audit: the admin INSERT and its audit_logs event
 *       (PLATFORM_ADMIN_BOOTSTRAPPED) commit or roll back TOGETHER in one
 *       database transaction, so the privileged record can never exist
 *       without its audit trail (PRD FR-09, AGENTS.md rule 9).</li>
 *   <li>Only a BCrypt hash is stored; the raw password never touches the
 *       database, logs or any file. The audit event records email and full
 *       name only - never the password or its hash.</li>
 * </ul>
 *
 * <p>Used by {@link PlatformAdminBootstrap} (thin main wrapper) and by the
 * automated tests; this class itself has no console/exit logic.
 */
public final class PlatformAdminBootstrapper {

    /** Fixed advisory-lock key so concurrent bootstrap attempts serialize. */
    public static final long BOOTSTRAP_LOCK_KEY = 76_202_610_28L;

    /** Schema version this build expects (V1..V6). */
    public static final int EXPECTED_SCHEMA_VERSION = 6;

    private final Supplier<Connection> connectionSupplier;
    private final PasswordEncoder passwordEncoder;

    public PlatformAdminBootstrapper(Supplier<Connection> connectionSupplier,
            PasswordEncoder passwordEncoder) {
        this.connectionSupplier = connectionSupplier;
        this.passwordEncoder = passwordEncoder;
    }

    /** @return the email of the created platform administrator (never the password). */
    public String createInitialPlatformAdmin(String email, String fullName, String rawPassword) {
        return createInitialPlatformAdmin(email, fullName, rawPassword, EXPECTED_SCHEMA_VERSION);
    }

    String createInitialPlatformAdmin(String email, String fullName, String rawPassword,
            int expectedSchemaVersion) {

        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        String normalizedFullName = fullName == null ? "" : fullName.trim();
        if (normalizedEmail.isEmpty() || !normalizedEmail.contains("@")) {
            throw new IllegalArgumentException("a valid email is required");
        }
        if (normalizedFullName.isEmpty()) {
            throw new IllegalArgumentException("full name is required");
        }
        PasswordPolicy.validate(rawPassword);

        try (Connection connection = connectionSupplier.get()) {
            int schemaVersion = currentSchemaVersion(connection);
            if (schemaVersion != expectedSchemaVersion) {
                throw new BootstrapException("database schema is at version " + schemaVersion
                        + " but this build expects " + expectedSchemaVersion
                        + "; run the approved migrations first. No changes were made.");
            }

            if (!tryAdvisoryLock(connection)) {
                throw new BootstrapException(
                        "another bootstrap appears to be running (advisory lock held). "
                                + "No changes were made.");
            }
            try {
                if (platformAdminExists(connection)) {
                    throw new BootstrapException(
                            "a platform administrator already exists; the bootstrap is "
                                    + "one-time and was refused. No changes were made.");
                }
                String passwordHash = passwordEncoder.encode(rawPassword);
                insertPlatformAdminWithAudit(connection, normalizedEmail, passwordHash,
                        normalizedFullName);
            } finally {
                advisoryUnlock(connection);
            }
            return normalizedEmail;

        } catch (SQLException e) {
            throw new BootstrapException(
                    "database error during bootstrap (SQLSTATE " + e.getSQLState()
                            + "). No changes were made.", e);
        }
    }

    private static int currentSchemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            var resultSet = statement.executeQuery(
                    "SELECT max(version::int) FROM flyway_schema_history WHERE success");
            if (resultSet.next()) {
                return resultSet.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new BootstrapException(
                    "cannot verify the Flyway schema history; the database is probably "
                            + "not migrated yet. No changes were made.", e);
        }
    }

    private static boolean platformAdminExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT EXISTS (SELECT 1 FROM users WHERE is_platform_admin)")) {
            return resultSet.next() && resultSet.getBoolean(1);
        }
    }

    private static boolean tryAdvisoryLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT pg_try_advisory_lock(" + BOOTSTRAP_LOCK_KEY + ")")) {
            return resultSet.next() && resultSet.getBoolean(1);
        }
    }

    private static void advisoryUnlock(Connection connection) {
        try (Statement statement = connection.createStatement();
                var resultSet = statement.executeQuery(
                        "SELECT pg_advisory_unlock(" + BOOTSTRAP_LOCK_KEY + ")")) {
            resultSet.next();
        } catch (SQLException ignored) {
            // The lock is released automatically when the connection closes.
        }
    }

    /**
     * Inserts the platform administrator AND the corresponding audit event in
     * ONE database transaction: either both rows exist afterwards, or neither
     * does. The audit event is a minimized change summary - it never contains
     * the password or its hash.
     */
    private static void insertPlatformAdminWithAudit(Connection connection, String email,
            String passwordHash, String fullName) throws SQLException {

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long userId = insertPlatformAdmin(connection, email, passwordHash, fullName);
            insertBootstrapAuditEvent(connection, userId, email, fullName);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static long insertPlatformAdmin(Connection connection, String email, String passwordHash,
            String fullName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (email, password_hash, full_name, status, is_platform_admin) "
                        + "VALUES (?, ?, ?, 'ACTIVE', TRUE)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            statement.setString(3, fullName);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("users insert returned no generated key");
                }
                return keys.getLong(1);
            }
        }
    }

    /** System-generated, platform-wide audit event (no company, no user actor). */
    private static void insertBootstrapAuditEvent(Connection connection, long userId, String email,
            String fullName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_logs (company_id, actor_user_id, action, entity_type, "
                        + "entity_id, after_data, reason, request_id) "
                        + "VALUES (NULL, NULL, 'PLATFORM_ADMIN_BOOTSTRAPPED', 'USER', ?, "
                        + "?::jsonb, 'one-time platform administrator bootstrap', "
                        + "'platform-admin-bootstrap')")) {
            statement.setLong(1, userId);
            statement.setString(2, auditAfterDataJson(email, fullName));
            statement.executeUpdate();
        }
    }

    /**
     * Minimized JSON change summary for the audit event: identity fields only,
     * never the password or its hash.
     */
    private static String auditAfterDataJson(String email, String fullName) {
        return "{\"email\":\"" + jsonEscape(email) + "\",\"full_name\":\"" + jsonEscape(fullName)
                + "\",\"status\":\"ACTIVE\",\"is_platform_admin\":true}";
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
