package com.cpdts.security;

import java.io.Serializable;

/**
 * Authenticated user identity carried in the security context / HTTP session.
 *
 * <p>Only non-sensitive profile data is kept here. Passwords are never part of
 * the principal. Permissions are intentionally NOT cached here: they are
 * resolved per request from the database (active memberships + role grants)
 * by {@code AccessControlService}, so permission or membership changes take
 * effect immediately.
 *
 * @param userId        users.id of the authenticated user
 * @param email         users.email (login identifier)
 * @param fullName      users.full_name
 * @param platformAdmin users.is_platform_admin (platform-wide access flag)
 */
public record CpdtsPrincipal(long userId, String email, String fullName, boolean platformAdmin)
        implements Serializable {
}
