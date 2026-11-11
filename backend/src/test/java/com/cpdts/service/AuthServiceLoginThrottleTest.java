package com.cpdts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import com.cpdts.repository.UserRepository;
import com.cpdts.repository.UserRepository.UserRow;
import com.cpdts.security.CpdtsUserDetails;
import com.cpdts.security.LoginThrottle;
import com.cpdts.service.AccessControlService;
import com.cpdts.support.MutableClock;
import com.cpdts.web.LoginThrottledException;

/**
 * AuthService-level throttle behaviour: normalized-email keying, the check
 * running BEFORE any account lookup (unknown/existing parity), trusted client
 * address vs spoofable forwarded headers, and success/disabled accounting.
 *
 * <p>Database-independent: repositories and the authentication manager are
 * mocked; the throttle itself is real, driven by a controllable clock.
 */
class AuthServiceLoginThrottleTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TRUSTED_IP = "198.51.100.7";
    private static final String FORWARDED_IP = "203.0.113.9";

    private MutableClock clock;
    private LoginThrottle throttle;
    private AuthenticationManager authenticationManager;
    private UserRepository users;
    private SecurityContextRepository securityContextRepository;
    private AuthService authService;

    /** Counts real authenticate() calls (stubbing itself must not count). */
    private final AtomicInteger authenticateCalls = new AtomicInteger();
    /** When set, authentication succeeds for this normalized email. */
    private volatile String successEmail;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        throttle = new LoginThrottle(2, 2, 300, 100, clock);
        authenticateCalls.set(0);
        successEmail = null;

        authenticationManager = mock(AuthenticationManager.class);
        when(authenticationManager.authenticate(any())).thenAnswer(invocation -> {
            authenticateCalls.incrementAndGet();
            UsernamePasswordAuthenticationToken token = invocation.getArgument(0);
            if (successEmail != null && successEmail.equals(token.getPrincipal())) {
                CpdtsUserDetails details = new CpdtsUserDetails(7L, successEmail, "Test",
                        "$2a$hash");
                return new UsernamePasswordAuthenticationToken(details, null, List.of());
            }
            // Unknown email and wrong password: the identical failure.
            throw new BadCredentialsException("invalid credentials");
        });

        users = mock(UserRepository.class);
        AccessControlService accessControl = mock(AccessControlService.class);
        when(accessControl.companiesFor(anyLong())).thenReturn(List.of());
        securityContextRepository = mock(SecurityContextRepository.class);
        authService = new AuthService(authenticationManager, users, accessControl,
                securityContextRepository, throttle);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Normalized emails
    // ------------------------------------------------------------------

    @Test
    void failuresKeyOnTheNormalizedEmailAcrossCaseAndWhitespace() {
        assertThatThrownBy(() -> attempt("  User@Example.TEST ", "wrong", request(TRUSTED_IP)))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> attempt("user@example.test", "wrong", request(TRUSTED_IP)))
                .isInstanceOf(BadCredentialsException.class);

        // Third attempt with yet another case variant: same normalized key.
        assertThatThrownBy(() -> attempt("USER@EXAMPLE.TEST", "wrong", request(TRUSTED_IP)))
                .isInstanceOf(LoginThrottledException.class);
        // The throttled attempt never reached credential verification.
        assertThat(authenticateCalls.get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Throttled BEFORE account lookup: unknown vs existing parity
    // ------------------------------------------------------------------

    @Test
    void throttleCheckRunsBeforeAnyAccountLookupForUnknownAndExistingAccounts() {
        throttle.recordFailure("unknown@example.test", TRUSTED_IP);
        throttle.recordFailure("unknown@example.test", TRUSTED_IP);
        throttle.recordFailure("known@example.test", TRUSTED_IP);
        throttle.recordFailure("known@example.test", TRUSTED_IP);

        LoginThrottledException unknown = assertThrows(LoginThrottledException.class,
                () -> attempt("unknown@example.test", "whatever", request(TRUSTED_IP)));
        LoginThrottledException existing = assertThrows(LoginThrottledException.class,
                () -> attempt("known@example.test", "correct-password", request(TRUSTED_IP)));

        // Identical cooldown => identical Retry-After for both, and no account
        // or credential work happened for either (no enumeration signal).
        assertThat(existing.retryAfterSeconds()).isEqualTo(unknown.retryAfterSeconds());
        assertThat(authenticateCalls.get()).isZero();
        verify(users, never()).findById(anyLong());
    }

    // ------------------------------------------------------------------
    // Trusted client address only
    // ------------------------------------------------------------------

    @Test
    void keysOnConnectionRemoteAddrAndIgnoresXForwardedFor() {
        MockHttpServletRequest spoofed = request(TRUSTED_IP);
        spoofed.addHeader("X-Forwarded-For", FORWARDED_IP);
        assertThatThrownBy(() -> attempt("a@example.test", "wrong", spoofed))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> attempt("b@example.test", "wrong", spoofed))
                .isInstanceOf(BadCredentialsException.class);

        // Both failures counted against the CONNECTION address: a third
        // attempt from it is throttled regardless of the spoofed header.
        assertThatThrownBy(() -> attempt("c@example.test", "wrong", request(TRUSTED_IP)))
                .isInstanceOf(LoginThrottledException.class);

        // The client-supplied forwarded value is NOT a throttle key: from the
        // real connection address FORWARDED_IP nothing is recorded, and a
        // header claiming to be TRUSTED_IP does not trigger a cooldown.
        assertThat(throttle.cooldownRemaining("c@example.test", FORWARDED_IP)).isEmpty();
        MockHttpServletRequest claim = request(FORWARDED_IP);
        claim.addHeader("X-Forwarded-For", TRUSTED_IP);
        assertThatThrownBy(() -> attempt("c@example.test", "wrong", claim))
                .isInstanceOf(BadCredentialsException.class);
    }

    // ------------------------------------------------------------------
    // Successful login clears buckets
    // ------------------------------------------------------------------

    @Test
    void successfulLoginClearsAccountAndAddressBuckets() {
        assertThatThrownBy(() -> attempt("person@example.test", "wrong", request(TRUSTED_IP)))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> attempt("person@example.test", "wrong", request(TRUSTED_IP)))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(throttle.cooldownRemaining("person@example.test", TRUSTED_IP)).isPresent();

        // Correct credentials during an active cooldown are still rejected
        // (temporary cooldown applies to every attempt)...
        assertThatThrownBy(() -> attempt("person@example.test", "right", request(TRUSTED_IP)))
                .isInstanceOf(LoginThrottledException.class);

        // ...and after expiry a successful sign-in clears both buckets.
        clock.advance(Duration.ofSeconds(300));
        successEmail = "person@example.test";
        when(users.findById(7L)).thenReturn(Optional.of(
                new UserRow(7L, "person@example.test", "Person", "$2a$hash", "ACTIVE", false)));
        authService.login("person@example.test", "right", request(TRUSTED_IP),
                new MockHttpServletResponse());

        assertThat(throttle.recordedFailuresForAccount("person@example.test")).isZero();
        assertThat(throttle.cooldownRemaining("person@example.test", TRUSTED_IP)).isEmpty();

        // A later single failure starts from zero (not threshold - 1).
        successEmail = null; // back to "every password is wrong"
        assertThatThrownBy(() -> attempt("person@example.test", "wrong", request(TRUSTED_IP)))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(throttle.cooldownRemaining("person@example.test", TRUSTED_IP)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Disabled accounts count as failures
    // ------------------------------------------------------------------

    @Test
    void disabledAccountWithCorrectPasswordStillCountsAsAFailure() {
        successEmail = "disabled@example.test";
        when(users.findById(7L)).thenReturn(Optional.of(
                new UserRow(7L, "disabled@example.test", "Disabled", "$2a$hash", "DISABLED",
                        false)));

        assertThatThrownBy(() -> attempt("disabled@example.test", "right", request(TRUSTED_IP)))
                .isInstanceOf(DisabledException.class);
        assertThat(throttle.recordedFailuresForAccount("disabled@example.test")).isEqualTo(1);
        assertThat(throttle.cooldownRemaining("disabled@example.test", TRUSTED_IP)).isEmpty();
    }

    @Test
    void unknownAccountFailureIsRecordedAgainstItsBuckets() {
        assertThatThrownBy(() -> attempt("ghost@example.test", "wrong", request(TRUSTED_IP)))
                .isInstanceOf(BadCredentialsException.class);
        assertThat(throttle.recordedFailuresForAccount("ghost@example.test")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Object attempt(String email, String password, MockHttpServletRequest request) {
        return authService.login(email, password, request, new MockHttpServletResponse());
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
