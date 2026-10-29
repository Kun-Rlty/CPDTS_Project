package com.cpdts.config;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import com.cpdts.security.ActiveUserAuthenticationFilter;
import com.cpdts.security.CpdtsUserDetailsService;
import com.cpdts.service.AccessControlService;

/**
 * Security configuration for the authentication foundation.
 *
 * <p>PROVISIONAL (surfaced for approval, reversible before business APIs):
 * server-side HTTP sessions instead of JWTs - the token strategy is still an
 * open decision in docs/TECHNICAL_REQUIREMENTS.md section 14.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            AccessControlService accessControlService,
            SecurityContextRepository securityContextRepository,
            HttpSessionCsrfTokenRepository csrfTokenRepository) throws Exception {

        // Deliberately NOT a bean: avoids double registration as a plain
        // servlet filter outside the security chain.
        ActiveUserAuthenticationFilter activeUserFilter =
                new ActiveUserAuthenticationFilter(accessControlService);

        http
            // CSRF: the token is stored in the HTTP session and delivered to
            // clients by GET /api/v1/auth/csrf; clients must echo it in the
            // X-XSRF-TOKEN header on POST/PUT/PATCH/DELETE.
            // Plain (non-XOR) handler: the stored token and the header carry
            // the SAME value - the deterministic flow for an API + JavaScript
            // client. (The XOR handler masks tokens rendered into HTML pages;
            // not applicable here.)
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/api/v1/auth/login",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/csrf",
                        "/actuator/health",
                        "/error").permitAll()
                .anyRequest().authenticated())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(jsonAuthenticationEntryPoint())
                .accessDeniedHandler(jsonAccessDeniedHandler()))
            // Runs before authorization: a user deactivated since login is
            // demoted to anonymous on this very request.
            .addFilterBefore(activeUserFilter, BasicAuthenticationFilter.class)
            .headers(headers -> headers
                .referrerPolicy(ref -> ref
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));

        return http.build();
    }

    /**
     * Shared CSRF repository: the token is stored in the HTTP session (the
     * same session that carries authentication). Clients fetch it once from
     * GET /api/v1/auth/csrf and echo it in the X-XSRF-TOKEN header on
     * POST/PUT/PATCH/DELETE.
     */
    @Bean
    public HttpSessionCsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        // X-XSRF-TOKEN is the convention JavaScript clients (e.g. Axios)
        // use when echoing the session CSRF token.
        repository.setHeaderName("X-XSRF-TOKEN");
        return repository;
    }

    /**
     * Shared session-backed security context repository (same instance used
     * for saving at login and loading on later requests).
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new HttpSessionSecurityContextRepository(),
                new RequestAttributeSecurityContextRepository());
    }

    /** Adaptive password hashing (BCrypt, cost 10). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /** Login authentication against the users table via BCrypt. */
    @Bean
    public AuthenticationManager authenticationManager(CpdtsUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        // Spring Security 7: UserDetailsService is a constructor argument.
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    private static AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return (request, response, authException) -> writeJson(response,
                HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated",
                "Authentication required");
    }

    private static AccessDeniedHandler jsonAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeJson(response,
                HttpServletResponse.SC_FORBIDDEN, "forbidden", "Access denied");
    }

    private static void writeJson(HttpServletResponse response, int status, String error,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }

    /**
     * CSRF request handler for the SPA flow.
     *
     * <p>Two deliberate choices:
     * <ul>
     *   <li>Plain (non-XOR) token resolution: the token stored server-side and
     *       the header sent by the client carry the SAME value - the
     *       deterministic flow for an API + JavaScript client. (The XOR
     *       handler masks tokens rendered into HTML pages to mitigate BREACH;
     *       not applicable here since clients read the token from a JSON
     *       response, not from HTML.)</li>
     *   <li>Accepts the {@code X-XSRF-TOKEN} header (the ecosystem convention
     *       used by Axios and most JavaScript clients) first, then falls back
     *       to the framework default ({@code X-CSRF-TOKEN} header /
     *       {@code _csrf} parameter).</li>
     * </ul>
     */
    static final class SpaCsrfTokenRequestHandler
            implements org.springframework.security.web.csrf.CsrfTokenRequestHandler {

        private static final String SPA_HEADER = "X-XSRF-TOKEN";

        private final CsrfTokenRequestAttributeHandler delegate =
                new CsrfTokenRequestAttributeHandler();

        @Override
        public void handle(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response,
                java.util.function.Supplier<org.springframework.security.web.csrf.CsrfToken> token) {
            delegate.handle(request, response, token);
        }

        @Override
        public String resolveCsrfTokenValue(jakarta.servlet.http.HttpServletRequest request,
                org.springframework.security.web.csrf.CsrfToken token) {
            String header = request.getHeader(SPA_HEADER);
            if (header != null && !header.isBlank()) {
                return header;
            }
            return delegate.resolveCsrfTokenValue(request, token);
        }
    }
}
