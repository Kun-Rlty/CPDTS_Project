package com.cpdts.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cpdts.service.AuthService;

/**
 * Authentication endpoints (foundation phase):
 * <ul>
 *   <li>POST /api/v1/auth/login - email + password -&gt; session cookie</li>
 *   <li>POST /api/v1/auth/logout - invalidates the session</li>
 *   <li>GET  /api/v1/auth/me - current user + accessible companies</li>
 *   <li>GET  /api/v1/auth/companies - accessible companies only</li>
 *   <li>GET  /api/v1/auth/csrf - issues the XSRF-TOKEN cookie for clients</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final HttpSessionCsrfTokenRepository csrfTokenRepository;

    public AuthController(AuthService authService,
            HttpSessionCsrfTokenRepository csrfTokenRepository) {
        this.authService = authService;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @PostMapping("/login")
    public AuthSessionResponse login(@RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        return authService.login(body == null ? null : body.email(),
                body == null ? null : body.password(), request, response);
    }

    @PostMapping("/logout")
    public java.util.Map<String, Object> logout(HttpServletRequest request,
            HttpServletResponse response) {
        authService.logout(request, response);
        return java.util.Map.of("ok", true);
    }

    @GetMapping("/me")
    public AuthSessionResponse me() {
        return authService.currentSession();
    }

    @GetMapping("/companies")
    public List<CompanyDto> companies() {
        return authService.currentCompanies();
    }

    /**
     * Issues the session CSRF token for clients: they call this once, then
     * echo the token in the X-XSRF-TOKEN header on POST/PUT/PATCH/DELETE.
     * The token is generated/loaded and saved EXPLICITLY through the shared
     * session repository (never through the filter's deferred request
     * attribute), so this response body and the value validated on later
     * requests are always identical.
     */
    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadToken(request);
        if (token == null) {
            token = csrfTokenRepository.generateToken(request);
        }
        csrfTokenRepository.saveToken(token, request, response);
        return new CsrfTokenResponse(token.getToken());
    }

    // ------------------------------------------------------------------
    // Request/response DTOs (no secrets ever leave the server)
    // ------------------------------------------------------------------

    public record LoginRequest(String email, String password) {
    }

    public record UserDto(long id, String email, String fullName, boolean platformAdmin) {
    }

    /** role is the membership role code, or null for platform-wide access. */
    public record CompanyDto(long id, String code, String displayName, String role) {
    }

    public record AuthSessionResponse(UserDto user, List<CompanyDto> companies) {
    }

    public record CsrfTokenResponse(String token) {
    }
}
