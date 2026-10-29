package com.cpdts.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cpdts.service.AccessControlService;

/**
 * Re-validates the logged-in user on EVERY request (server-side, not just at
 * login time): if the user was deleted or deactivated since the session was
 * created, the security context is cleared and the session invalidated, so the
 * request is treated as unauthenticated (401 on protected endpoints).
 *
 * <p>This is what enforces "only active users are served", independently of
 * what the client or the session cookie claims.
 *
 * <p>Instantiated inside SecurityConfig (deliberately NOT a Spring bean) so it
 * is not auto-registered as a plain servlet filter outside the security chain.
 */
public final class ActiveUserAuthenticationFilter extends OncePerRequestFilter {

    private final AccessControlService accessControlService;

    public ActiveUserAuthenticationFilter(AccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof CpdtsAuthenticationToken token
                && !accessControlService.isUserActive(token.principal().userId())) {
            // User no longer exists or is no longer ACTIVE: drop the session.
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }

        filterChain.doFilter(request, response);
    }
}
