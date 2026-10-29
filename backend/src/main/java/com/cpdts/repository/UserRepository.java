package com.cpdts.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read/update access to the users table for authentication and (since the
 * user-management phase) user creation.
 *
 * <p>Deliberately minimal: creates users with an EXISTING password hash only;
 * there is no password-change, global-status-change or profile-update method -
 * those operations are not part of the approved user-management scope (they
 * would let company admins touch global account data of users who belong to
 * both companies).
 */
@Repository
public class UserRepository {

    private static final String COLUMNS = "id, email, full_name, password_hash, status, is_platform_admin";

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByEmail(String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE email = ?",
                (rs, rowNum) -> mapRow(rs), email).stream().findFirst();
    }

    public Optional<UserRow> findById(long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = ?",
                (rs, rowNum) -> mapRow(rs), userId).stream().findFirst();
    }

    public void touchLastLogin(long userId) {
        jdbc.update("UPDATE users SET last_login_at = now(), updated_at = now() WHERE id = ?", userId);
    }

    /**
     * Inserts a new ACTIVE, non-platform-admin user and returns its id.
     *
     * <p>{@code is_platform_admin} is written EXPLICITLY as FALSE (never taken
     * from any request): the platform-admin flag is written solely by the
     * one-time bootstrap. The password is always an already-encoded BCrypt
     * hash - raw passwords never reach this class.
     */
    public long insert(String email, String passwordHash, String fullName) {
        Long id = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, status, is_platform_admin)
                VALUES (?, ?, ?, 'ACTIVE', FALSE)
                RETURNING id
                """, Long.class, email, passwordHash, fullName);
        if (id == null) {
            throw new IllegalStateException("users insert returned no id");
        }
        return id;
    }

    private static UserRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getBoolean("is_platform_admin"));
    }

    /**
     * Immutable snapshot of one users row (never exposed outside the backend).
     */
    public record UserRow(long id, String email, String fullName, String passwordHash,
            String status, boolean platformAdmin) {

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }
    }
}
