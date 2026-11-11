package com.cpdts.web;

import java.time.Duration;

/**
 * Thrown BEFORE credential verification when a temporary login cooldown is
 * active for the account key or the client address.
 *
 * <p>The check runs before any account lookup or password check, so the
 * resulting 429 response is byte-identical for unknown and existing accounts
 * and can never be used as an enumeration oracle. The cooldown is temporary:
 * counters age out after the configured window (see
 * {@code com.cpdts.security.LoginThrottle}).
 */
public class LoginThrottledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public LoginThrottledException(Duration cooldown) {
        super("login throttled");
        // Retry-After is delta-seconds: round UP so the client never retries
        // a millisecond before the cooldown actually expires.
        this.retryAfterSeconds = Math.max(1, (cooldown.toMillis() + 999) / 1000);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
