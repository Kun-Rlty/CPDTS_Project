package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.cpdts.repository.UserRepository;
import com.cpdts.security.LoginThrottle;
import com.cpdts.service.AccessControlService;
import com.cpdts.service.AuthService;
import com.cpdts.support.MutableClock;

/**
 * HTTP contract of the login throttle at POST /api/v1/auth/login:
 * 401 below the threshold, then JSON 429 + Retry-After with a body that is
 * byte-identical for unknown and existing accounts.
 *
 * <p>Standalone MockMvc (no Spring context, no database): AuthService gets a
 * real LoginThrottle with a controllable clock and an AuthenticationManager
 * that always fails, so every login is a genuine failure.
 */
class LoginThrottleEndpointTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private MutableClock clock;
    private LoginThrottle throttle;
    private AuthenticationManager authenticationManager;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        // Prototype account threshold (5) with a high IP threshold so this
        // test exercises the ACCOUNT bucket deterministically.
        throttle = new LoginThrottle(5, 1000, 300, 100, clock);
        authenticationManager = mock(AuthenticationManager.class);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("invalid credentials"));
        UserRepository users = mock(UserRepository.class);
        AccessControlService accessControl = mock(AccessControlService.class);
        SecurityContextRepository securityContextRepository =
                mock(SecurityContextRepository.class);
        AuthService authService = new AuthService(authenticationManager, users, accessControl,
                securityContextRepository, throttle);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService,
                        new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void belowThresholdKeepsReturningIdenticalInvalidCredentials() throws Exception {
        MvcResult first = mockMvc.perform(login("ghost@example.test", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andReturn();
        MvcResult second = mockMvc.perform(login("ghost@example.test", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andReturn();

        // Unknown account: 401 + no Retry-After (this response is unchanged
        // by the throttle feature).
        assertThat(first.getResponse().getHeader("Retry-After")).isNull();
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void returnsJson429WithRetryAfterOnceTheAccountThresholdIsReached() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login("ghost@example.test", "wrong"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login("ghost@example.test", "wrong"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.error").value("login_throttled"))
                .andExpect(jsonPath("$.message")
                        .value("Too many sign-in attempts. Please try again later."));
    }

    @Test
    void throttledResponseIsIdenticalForUnknownAndExistingAccounts() throws Exception {
        // Seed both buckets to the threshold directly (equivalent to five
        // recorded failures each) so neither request reaches authentication.
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("unknown@example.test", "127.0.0.1");
            throttle.recordFailure("existing@example.test", "127.0.0.1");
        }

        MvcResult unknown = mockMvc.perform(login("unknown@example.test", "wrong"))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        MvcResult existing = mockMvc.perform(login("existing@example.test", "right"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        // Byte-identical 429 body and Retry-After: nothing about the account
        // (existence, password correctness) leaks through the throttle.
        assertThat(existing.getResponse().getStatus()).isEqualTo(429);
        assertThat(existing.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(existing.getResponse().getHeader("Retry-After"))
                .isEqualTo(unknown.getResponse().getHeader("Retry-After"))
                .isEqualTo("300");
    }

    @Test
    void cooldownExpiresAndLoginAttemptsAreEvaluatedAgain() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login("ghost@example.test", "wrong"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("ghost@example.test", "wrong"))
                .andExpect(status().isTooManyRequests());

        // Attempts during the cooldown are NOT recorded: advancing exactly
        // the cooldown window clears it (temporary, never a lockout).
        mockMvc.perform(login("ghost@example.test", "wrong"))
                .andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(300));

        mockMvc.perform(login("ghost@example.test", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }

    @Test
    void retryAfterRoundsUpFractionalRemainingTime() throws Exception {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("ghost@example.test", "127.0.0.1");
        }
        // 299.5 s into a 300 s window: remaining = 0.5 s -> Retry-After 1
        // (rounds UP so the client never retries before the cooldown expires).
        clock.advance(Duration.ofMillis(299_500L));
        mockMvc.perform(login("ghost@example.test", "wrong"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            login(String email, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }
}
