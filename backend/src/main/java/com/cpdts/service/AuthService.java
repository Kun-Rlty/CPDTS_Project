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
import com.cpdts.security.LoginThrottle;
import com.cpdts.security.PasswordPolicy;
import com.cpdts.web.AuthController.AuthSessionResponse;
import com.cpdts.web.AuthController.CompanyDto;
import com.cpdts.web.AuthController.UserDto;
import com.cpdts.web.LoginThrottledException;

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
 *   <li>Bounded login-abuse protection: a temporary cooldown is enforced
 *       BEFORE any account lookup or password check, keyed by the normalized
 *       email and the connection's remote address (never a client-supplied
 *       forwarded header - proxy trust is enforced by TrustedProxyHeadersFilter
 *       and Tomcat's RemoteIpValve in the proxy profile). While throttled the
 *       response is the identical generic 429 + Retry-After for every account;
 *       failed attempts increment both buckets, a successful sign-in clears
 *       them, and counters age out after the cooldown (no permanent lockout).</li>
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
    private final LoginThrottle loginThrottle;

    public AuthService(AuthenticationManager authenticationManager, UserRepository users,
            AccessControlService accessControlService,
            SecurityContextRepository securityContextRepository, LoginThrottle loginThrottle) {
        this.authenticationManager = authenticationManager;
        this.users = users;
        this.accessControlService = accessControlService;
        this.securityContextRepository = securityContextRepository;
        this.loginThrottle = loginThrottle;
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

        // Trusted client address: getRemoteAddr() is rewritten by Tomcat's
        // RemoteIpValve ONLY for connections from the configured internal
        // proxy (proxy profile), and spoofed forwarded headers never reach
        // here (TrustedProxyHeadersFilter). In local HTTP development it is
        // the direct peer address.
        String clientIp = request.getRemoteAddr();

        // Throttle check BEFORE any credential/account work: the 429 response
        // cannot depend on whether the account exists.
        java.util.Optional<java.time.Duration> cooldown =
                loginThrottle.cooldownRemaining(normalizedEmail, clientIp);
        if (cooldown.isPresent()) {
            throw new LoginThrottledException(cooldown.get());
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalizedEmail, rawPassword));
        } catch (BadCredentialsException | DisabledException failure) {
            // Unknown email and wrong password take this identical path, so
            // both count against the same buckets (no enumeration signal).
            loginThrottle.recordFailure(normalizedEmail, clientIp);
            throw failure;
        }

        long userId = ((CpdtsUserDetails) authentication.getPrincipal()).userId();
        java.util.Optional<UserRow> found = users.findById(userId);
        if (found.isEmpty()) {
            loginThrottle.recordFailure(normalizedEmail, clientIp);
            throw new BadCredentialsException("invalid credentials");
        }
        UserRow user = found.get();
        if (!user.isActive()) {
            // Only reached with a CORRECT password (see class javadoc).
            loginThrottle.recordFailure(normalizedEmail, clientIp);
            throw new DisabledException("account disabled");
        }

        // Credentials verified: clear this account's and this client's
        // failure buckets before establishing the session.
        loginThrottle.recordSuccess(normalizedEmail, clientIp);

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
                .map(c -> new CompanyDto(c.companyId(), c.code(), c.displayName(), c.roleCode(),
                        accessControlService.permissionsFor(userId, c.companyId())))
                .toList();
    }
}
