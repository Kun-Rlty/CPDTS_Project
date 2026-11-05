package com.cpdts.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces the boundary between the public HTTPS reverse proxy and this
 * application.
 *
 * <p>Forwarded headers are only meaningful when they come from the reverse
 * proxy. The proxy overwrites all client-supplied {@code Forwarded} /
 * {@code X-Forwarded-*} values and adds a shared boundary token. This filter
 * rejects any request that carries forwarded headers without that exact token,
 * so a client or local process cannot spoof the original scheme, host or
 * address directly against the application.
 *
 * <p>When no token is configured (local HTTP development), the filter is
 * inactive. In that mode forwarded headers are not processed by the server
 * either, because {@code server.forward-headers-strategy} is only enabled in
 * the {@code proxy} profile.
 */
public class TrustedProxyHeadersFilter extends OncePerRequestFilter {

    static final String PROXY_TOKEN_HEADER = "X-CPDTS-Proxy-Token";

    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "forwarded",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-port",
            "x-forwarded-prefix",
            "x-forwarded-proto",
            "x-forwarded-server",
            "x-forwarded-ssl",
            "x-real-ip");

    private final byte[] expectedToken;

    public TrustedProxyHeadersFilter(String token) {
        this.expectedToken = token == null || token.isBlank()
                ? null
                : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (expectedToken == null || !hasForwardedHeaders(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String supplied = request.getHeader(PROXY_TOKEN_HEADER);
        if (supplied == null
                || !MessageDigest.isEqual(expectedToken, supplied.getBytes(StandardCharsets.UTF_8))) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static boolean hasForwardedHeaders(HttpServletRequest request) {
        for (String name : Collections.list(request.getHeaderNames())) {
            if (FORWARDED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"error\":\"untrusted_forwarded_headers\","
                        + "\"message\":\"Forwarded headers are accepted only from the configured proxy\"}");
    }
}
