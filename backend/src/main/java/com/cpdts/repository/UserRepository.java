package com.cpdts.repository;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read/update access to the users table for authentication purposes.
 *
 * <p>Deliberately minimal: no password changes, no user management in this
 * phase (user management is a later approved phase using USER_MANAGE).
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
