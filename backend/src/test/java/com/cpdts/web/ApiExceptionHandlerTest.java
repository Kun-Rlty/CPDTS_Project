package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    @Test
    void unknownResourcePathReturnsJsonNotFound() {
        ResponseEntity<java.util.Map<String, String>> response =
                new ApiExceptionHandler().handleNoResource();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .containsEntry("error", "not_found")
                .containsEntry("message", "Unknown endpoint");
    }

    @Test
    void loginThrottledReturns429WithRetryAfterAndStaticBody() {
        ResponseEntity<java.util.Map<String, String>> response =
                new ApiExceptionHandler().handleLoginThrottled(
                        new LoginThrottledException(java.time.Duration.ofSeconds(300)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER))
                .isEqualTo("300");
        assertThat(response.getBody())
                .containsEntry("error", "login_throttled")
                .containsEntry("message", "Too many sign-in attempts. Please try again later.");
    }

    @Test
    void loginThrottledRetryAfterRoundsUpAndNeverDropsBelowOneSecond() {
        ApiExceptionHandler handler = new ApiExceptionHandler();

        // 1.5 s remaining -> 2 s (client must not retry before expiry).
        ResponseEntity<java.util.Map<String, String>> fractional = handler.handleLoginThrottled(
                new LoginThrottledException(java.time.Duration.ofMillis(1500)));
        assertThat(fractional.getHeaders()
                .getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER)).isEqualTo("2");

        // Sub-second remaining -> still at least 1 (header is delta-seconds).
        ResponseEntity<java.util.Map<String, String>> tiny = handler.handleLoginThrottled(
                new LoginThrottledException(java.time.Duration.ofMillis(1)));
        assertThat(tiny.getHeaders().getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER))
                .isEqualTo("1");
    }
}
