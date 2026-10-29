package com.cpdts.security;

import java.nio.charset.StandardCharsets;

/**
 * Provisional password policy (surfaced for approval in the auth foundation
 * report). Applied wherever a password is chosen; can be tightened centrally.
 *
 * <p>BCrypt operates on BYTES, not characters: input longer than 72 UTF-8
 * bytes cannot be hashed (Spring Security's BCrypt rejects it outright - it
 * never truncates silently). This policy therefore measures the maximum
 * length in UTF-8 bytes so multibyte passwords (CJK, emoji, accented text)
 * are judged against the same limit the hash function enforces, and
 * violations are reported clearly at the policy layer instead of surfacing
 * as a low-level encoder exception deep inside authentication.
 *
 * <p>Location note: this class lives in {@code com.cpdts.security} (not
 * bootstrap) because both the bootstrap command and the login service depend
 * on it; neither should depend on the other.
 */
public final class PasswordPolicy {

    /** Provisional minimum length, in characters. */
    public static final int MIN_LENGTH = 12;

    /**
     * BCrypt's hard limit, in UTF-8 bytes. Longer input is rejected by the
     * hash function; measuring bytes (not chars) keeps multibyte passwords
     * honest - e.g. 25 CJK characters are 75 bytes and are NOT acceptable
     * even though 25 &lt; 72 characters.
     */
    public static final int MAX_LENGTH_BYTES = 72;

    private PasswordPolicy() {
    }

    /**
     * @throws IllegalArgumentException when the password violates the policy
     *         (too short, or longer than {@link #MAX_LENGTH_BYTES} UTF-8
     *         bytes - the bcrypt limit)
     */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "password must be at least " + MIN_LENGTH + " characters");
        }
        int bytes = utf8Length(password);
        if (bytes > MAX_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "password must be at most " + MAX_LENGTH_BYTES + " UTF-8 bytes (bcrypt limit); "
                            + "this password is " + bytes + " bytes over its "
                            + password.length() + " characters - shorten it; "
                            + "passwords are never truncated");
        }
    }

    /** UTF-8 byte length of the password - the unit bcrypt actually enforces. */
    public static int utf8Length(String password) {
        return password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
    }
}
