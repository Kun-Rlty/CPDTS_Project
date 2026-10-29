package com.cpdts.service;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import com.cpdts.repository.UserRepository;
import com.cpdts.repository.UserRepository.UserRow;
import com.cpdts.security.CpdtsAuthenticationToken;
import com.cpdts.security.CpdtsPrincipal;
import com.cpdts.security.CpdtsUserDetails;
import com.cpdts.security.EmailNormalizer;
import com.cpdts.security.PasswordPolicy;
import com.cpdts.web.AuthController.AuthSessionResponse;
import com.cpdts.web.AuthController.CompanyDto;
import com.cpdts.web.AuthController.UserDto;

/**
 * Login / logout / session queries for the authentication foundation.
 *
 * <p>Security properties:
 * <ul>
 *   <li>Unknown email and wrong password produce the SAME error
 *       (no account enumeration).</li>
 *   <li>Disabled accounts are rejected only AFTER password verification
 *       (DisabledException), so the "disabled" message is never an oracle for
 *       users who do not know the password.</li>
 *   <li>The password is verified by DaoAuthenticationProvider against the
 *       BCrypt hash; plaintext is never stored or logged. Inputs beyond
 *       BCrypt's 72-byte UTF-8 limit are rejected BEFORE hashing so they can
 *       never be silently truncated into a shorter equivalent password.</li>
 *   <li>On success the security context is stored in the HTTP session and the
 *       session ID is rotated (session-fixation protection); the
 *       ActiveUserAuthenticationFilter re-checks account status on every
 *       later request.</li>
 * </ul>
 */
@Service
public class AuthService {

    /** Provisional input cap for the login identifier. */
    static final int MAX_EMAIL_LENGTH = 320;

    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final AccessControlService accessControlService;
    private final SecurityContextRepository securityContextRepository;

    public AuthService(AuthenticationManager authenticationManager, UserRepository users,
            AccessControlService accessControlService,
            SecurityContextRepository securityContextRepository) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.accessControlService = accessControlService;
        this.securityContextRepository = securityContextRepository;
    }

    public AuthSessionResponse login(String email, String password,
            HttpServletRequest request, HttpServletResponse response) {

        String normalizedEmail = EmailNormalizer.normalize(email);
        String rawPassword = password == null ? "" : password;
        if (normalizedEmail.isEmpty() || rawPassword.isEmpty()) {
            throw new IllegalArgumentException("email and password are required");
        }
        if (normalizedEmail.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("invalid email length");
        }
        if (PasswordPolicy.utf8Length(rawPassword) > PasswordPolicy.MAX_LENGTH_BYTES) {
            // Reject BEFORE bcrypt: its 72-byte limit is BYTE-based, not
            // character-based, and passwords are never silently truncated.
            // (This also closes the truncation-equivalence attack: a longer
            // password sharing the first 72 bytes with the real one cannot
            // authenticate.)
            throw new IllegalArgumentException("password exceeds the maximum of "
                    + PasswordPolicy.MAX_LENGTH_BYTES + " UTF-8 bytes");
        }

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, rawPassword));

        long userId = ((CpdtsUserDetails) authentication.getPrincipal()).userId();
        UserRow user = users.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("invalid credentials"));
        if (!user.isActive()) {
            // Only reached with a CORRECT password (see class javadoc).
            throw new DisabledException("account disabled");
        }

        CpdtsPrincipal principal = new CpdtsPrincipal(user.id(), user.email(), user.fullName(),
                user.platformAdmin());

        // Session-fixation protection: if the client already had a session
        // (e.g. one created by GET /api/v1/auth/csrf), rotate its ID BEFORE
        // the authentication state is stored, so a pre-login session ID can
        // never carry authenticated state. (Fresh clients get a new session
        // from saveContext below - nothing to rotate.)
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(CpdtsAuthenticationToken.authenticated(principal));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        users.touchLastLogin(user.id());
        return sessionResponse(principal);
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    public AuthSessionResponse currentSession() {
        return sessionResponse(currentPrincipal());
    }

    public List<CompanyDto> currentCompanies() {
        return companyDtos(currentPrincipal().userId());
    }

    private CpdtsPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof CpdtsAuthenticationToken token) {
            return token.principal();
        }
        // Route-level protection normally prevents reaching this.
        throw new org.springframework.security.access.AccessDeniedException("not authenticated");
    }

    private AuthSessionResponse sessionResponse(CpdtsPrincipal principal) {
        UserDto user = new UserDto(principal.userId(), principal.email(), principal.fullName(),
                principal.platformAdmin());
        return new AuthSessionResponse(user, companyDtos(principal.userId()));
    }

    private List<CompanyDto> companyDtos(long userId) {
        return accessControlService.companiesFor(userId).stream()
                .map(c -> new CompanyDto(c.companyId(), c.code(), c.displayName(), c.roleCode()))
                .toList();
    }
}
