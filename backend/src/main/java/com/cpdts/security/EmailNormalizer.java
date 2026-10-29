package com.cpdts.security;

import java.util.Locale;

/**
 * The ONE place that turns a supplied email into the canonical login
 * identifier: trimmed and lowercased with {@link Locale#ROOT}.
 *
 * <p>Emails identify accounts, so every entry point must normalize them the
 * same way or the same person could end up with (or fail to reach) an
 * account depending on typing case:
 * <ul>
 *   <li>login ({@code AuthService} / {@code CpdtsUserDetailsService}),</li>
 *   <li>the one-time platform-admin bootstrap,</li>
 *   <li>user creation in {@code UserManagementService}.</li>
 * </ul>
 *
 * <p>{@code Locale.ROOT} (not the JVM default) is required: in a Turkish
 * default locale {@code "I".toLowerCase()} yields a dotless "ı", which would
 * silently create a different account.
 *
 * <p>Data contract (verified 2026-10-29 on cpdts_dev and cpdts_test): every
 * stored email is already trimmed and lowercase, and the V1 unique
 * constraint {@code uq_users_email} is case-sensitive. Because ALL writers
 * normalize through this class, stored data stays lowercase and exact-match
 * lookups on the normalized value remain correct without a case-insensitive
 * index or a data migration.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    /** Canonical form used for storage, lookup and login. */
    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
