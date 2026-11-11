package com.cpdts.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.cpdts.support.MutableClock;

/**
 * Unit tests for the bounded login-abuse throttle: thresholds, clock-driven
 * expiry, account/IP independence, concurrency, bounded storage, and
 * configuration validation. No database or Spring context required.
 */
class LoginThrottleTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ACCOUNT = "user@example.test";
    private static final String OTHER_ACCOUNT = "other@example.test";
    private static final String IP_ONE = "198.51.100.7";
    private static final String IP_TWO = "203.0.113.9";

    private final MutableClock clock = new MutableClock(T0);

    private LoginThrottle throttle(int accountFailures, int ipFailures, long cooldownSeconds,
            int maxEntries) {
        return new LoginThrottle(accountFailures, ipFailures, cooldownSeconds, maxEntries, clock);
    }

    // ------------------------------------------------------------------
    // Thresholds
    // ------------------------------------------------------------------

    @Test
    void accountCooldownStartsOnlyAtTheConfiguredThreshold() {
        LoginThrottle throttle = throttle(3, 100, 300, 100);

        throttle.recordFailure(ACCOUNT, IP_ONE);
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_TWO)).isEmpty();
        throttle.recordFailure(ACCOUNT, IP_ONE);
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_TWO)).isEmpty();

        throttle.recordFailure(ACCOUNT, IP_ONE);
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_TWO))
                .hasValue(Duration.ofSeconds(300));
    }

    @Test
    void ipCooldownBlocksEveryAccountFromThatAddress() {
        LoginThrottle throttle = throttle(100, 2, 300, 100);

        throttle.recordFailure(ACCOUNT, IP_ONE);
        assertThat(throttle.cooldownRemaining(OTHER_ACCOUNT, IP_ONE)).isEmpty();
        throttle.recordFailure(OTHER_ACCOUNT, IP_ONE);

        // Both account keys are individually below threshold: only the shared
        // client-address bucket is cooling this address down.
        assertThat(throttle.recordedFailuresForAccount(ACCOUNT)).isEqualTo(1);
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_ONE)).isPresent();
        // ...and a different address is unaffected.
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_TWO)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Clock-driven expiry (temporary cooldown, never lockout)
    // ------------------------------------------------------------------

    @Test
    void cooldownExpiresExactlyAfterTheWindowAndTheKeyIsPruned() {
        LoginThrottle throttle = throttle(2, 100, 300, 100);
        throttle.recordFailure(ACCOUNT, IP_ONE);
        throttle.recordFailure(ACCOUNT, IP_ONE);
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_ONE)).isPresent();
        // Two keys: the account bucket and the shared address bucket.
        assertThat(throttle.trackedKeys()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(299));
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_ONE)).isPresent();

        clock.advance(Duration.ofSeconds(1));
        Optional<Duration> expired = throttle.cooldownRemaining(ACCOUNT, IP_ONE);
        assertThat(expired).isEmpty();
        // Stale entry pruned on access: no permanent state, no lockout.
        assertThat(throttle.trackedKeys()).isZero();
    }

    @Test
    void rejectedAttemptsDuringCooldownNeverExtendIt() {
        LoginThrottle throttle = throttle(2, 100, 300, 100);
        throttle.recordFailure(ACCOUNT, IP_ONE);
        throttle.recordFailure(ACCOUNT, IP_ONE);
        Instant expiry = T0.plusSeconds(300);

        // Attacker keeps hammering during the cooldown.
        for (int i = 0; i < 50; i++) {
            assertThat(throttle.cooldownRemaining(ACCOUNT, IP_ONE)).isPresent();
            throttle.recordFailure(ACCOUNT, IP_ONE);
            clock.advance(Duration.ofSeconds(5));
        }

        // The original cooldown still expires on schedule (not extended).
        clock.set(expiry);
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_ONE)).isEmpty();
    }

    @Test
    void successClearsBothAccountAndIpBuckets() {
        LoginThrottle throttle = throttle(3, 3, 300, 100);
        throttle.recordFailure(ACCOUNT, IP_ONE);
        throttle.recordFailure(OTHER_ACCOUNT, IP_ONE);

        throttle.recordSuccess(ACCOUNT, IP_ONE);

        assertThat(throttle.recordedFailuresForAccount(ACCOUNT)).isZero();
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_ONE)).isEmpty();
        assertThat(throttle.cooldownRemaining(OTHER_ACCOUNT, IP_ONE)).isEmpty();
        // The other account's key remains (below threshold, harmless); the
        // account and shared address keys of the successful sign-in are gone.
        assertThat(throttle.trackedKeys()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    @Test
    void concurrentFailuresStayWithinTheThresholdAndNeverThrow() throws Exception {
        int threshold = 10;
        LoginThrottle throttle = throttle(threshold, 100, 300, 100);
        int threads = 8;
        int attemptsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < attemptsPerThread; i++) {
                        throttle.recordFailure(ACCOUNT, IP_ONE);
                        throttle.cooldownRemaining(ACCOUNT, IP_ONE);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // The deque never grows past the threshold and state stays bounded.
        assertThat(throttle.recordedFailuresForAccount(ACCOUNT)).isLessThanOrEqualTo(threshold);
        assertThat(throttle.trackedKeys()).isLessThanOrEqualTo(100);
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_ONE)).isPresent();
    }

    // ------------------------------------------------------------------
    // Bounded storage
    // ------------------------------------------------------------------

    @Test
    void storageIsBoundedAndExistingCooldownsKeepWorkingWhenFull() {
        int cap = 6;
        LoginThrottle throttle = throttle(2, 100, 300, cap);

        // One account reaches its cooldown (2 keys: account + address)...
        throttle.recordFailure("victim@example.test", "198.51.100.1");
        throttle.recordFailure("victim@example.test", "198.51.100.1");
        assertThat(throttle.cooldownRemaining("victim@example.test", "198.51.100.1"))
                .isPresent();
        // ...then distinct accounts fill the remaining capacity (2 keys each).
        throttle.recordFailure("spray-1@example.test", "198.51.100.2");
        throttle.recordFailure("spray-1@example.test", "198.51.100.2");
        throttle.recordFailure("spray-2@example.test", "198.51.100.3");
        throttle.recordFailure("spray-2@example.test", "198.51.100.3");
        assertThat(throttle.trackedKeys()).isEqualTo(cap);

        // Fail-open: a brand-new key is NOT tracked when capacity is
        // exhausted (memory cannot grow without limit)...
        throttle.recordFailure("fresh@example.test", "192.0.2.1");
        assertThat(throttle.trackedKeys()).isEqualTo(cap);
        assertThat(throttle.cooldownRemaining("fresh@example.test", "192.0.2.1")).isEmpty();
        // ...but an already-recorded cooldown still applies.
        assertThat(throttle.cooldownRemaining("victim@example.test", "198.51.100.1"))
                .isPresent();
    }

    @Test
    void staleKeysAreSweptWhenCapacityIsPressured() {
        int cap = 4;
        LoginThrottle throttle = throttle(2, 100, 300, cap);
        throttle.recordFailure(ACCOUNT, IP_ONE);
        throttle.recordFailure(ACCOUNT, IP_ONE);
        throttle.recordFailure(OTHER_ACCOUNT, IP_ONE);
        throttle.recordFailure(OTHER_ACCOUNT, IP_ONE);
        // Three keys: both account keys plus the shared address key.
        assertThat(throttle.trackedKeys()).isEqualTo(3);

        // Fill to capacity with fresh keys, then age the first two out.
        throttle.recordFailure("a3@example.test", "198.51.100.3");
        throttle.recordFailure("a3@example.test", "198.51.100.3");
        throttle.recordFailure("a4@example.test", "198.51.100.4");
        throttle.recordFailure("a4@example.test", "198.51.100.4");
        assertThat(throttle.trackedKeys()).isLessThanOrEqualTo(cap);

        clock.advance(Duration.ofSeconds(301));
        throttle.recordFailure("a5@example.test", "198.51.100.5");
        // Expired keys were swept during the capacity check.
        assertThat(throttle.trackedKeys()).isLessThanOrEqualTo(cap);
        assertThat(throttle.cooldownRemaining(ACCOUNT, IP_ONE)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Configuration validation
    // ------------------------------------------------------------------

    @Test
    void nonPositiveConfigurationIsRejectedAtStartup() {
        assertThatThrownBy(() -> new LoginThrottle(0, 20, 300, 100, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginThrottle(5, 0, 300, 100, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginThrottle(5, 20, 0, 100, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginThrottle(5, 20, 300, 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // Minimal logging contract
    // ------------------------------------------------------------------

    @Test
    void accountFingerprintNeverContainsTheEmailAddress() {
        String fingerprint = LoginThrottle.fingerprint(ACCOUNT);
        assertThat(fingerprint).hasSize(12).matches("[0-9a-f]{12}");
        assertThat(fingerprint).doesNotContain("user", "example");
        // Stable for correlation, distinct per account.
        assertThat(LoginThrottle.fingerprint(ACCOUNT)).isEqualTo(fingerprint);
        assertThat(LoginThrottle.fingerprint(OTHER_ACCOUNT)).isNotEqualTo(fingerprint);
    }
}
