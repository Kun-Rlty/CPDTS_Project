package com.cpdts.security;

import java.util.Collections;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Authentication token for a successfully authenticated CPDTS user.
 *
 * <p>Created only after password verification succeeds (or restored from the
 * HTTP session on later requests). Carries no authorities: authorization is
 * permission-based and company-scoped, resolved at service level via
 * {@code AccessControlService} (PRD: roles must be permission-based, not
 * role-name-based).
 */
public final class CpdtsAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final CpdtsPrincipal principal;

    private CpdtsAuthenticationToken(CpdtsPrincipal principal) {
        super(Collections.emptyList());
        this.principal = principal;
    }

    public static CpdtsAuthenticationToken authenticated(CpdtsPrincipal principal) {
        CpdtsAuthenticationToken token = new CpdtsAuthenticationToken(principal);
        token.setAuthenticated(true);
        return token;
    }

    public CpdtsPrincipal principal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        // Never retained after authentication.
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
