package com.cpdts.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded, temporary login-abuse throttling for the prototype.
 *
 * <p>Design properties:
 * <ul>
 *   <li><b>Two independent keys per attempt</b>: the normalized account
 *       identifier ({@code a:<email>}) and the trusted client address
 *       ({@code i:<remote address>}). A failure increments BOTH buckets, so an
 *       attacker spraying one password across many accounts is stopped by the
 *       IP bucket, while targeted guessing against one account is stopped by
 *       the account bucket regardless of source address.</li>
 *   <li><b>Temporary cooldown, never a permanent lockout</b>: each key keeps a
 *       sliding window of failure timestamps. The key cools down only until
 *       the oldest failures age out ({@code cooldown-seconds}); after that the
 *       bucket is empty again and a legitimate user can sign in. Attempts
 *       rejected during a cooldown are NOT recorded, so an attacker cannot
 *       extend someone's cooldown by hammering the endpoint, and a successful
 *       sign-in clears both buckets immediately.</li>
 *   <li><b>Bounded storage</b>: at most {@code max-entries} keys. Stale keys
 *       are pruned on access and swept when the map is full; if the map stays
 *       full, NEW keys are not tracked (fail-open) with a rate-limited
 *       warning - existing cooldowns keep working and memory cannot grow
 *       without limit.</li>
 *   <li><b>Single instance</b>: state lives in this JVM only. Counters are
 *       not shared between replicas and reset on restart. A multi-instance
 *       deployment needs a shared store (out of scope for the prototype).</li>
 *   <li><b>Minimal logging</b>: cooldown transitions are logged at WARN with
 *       a SHA-256 account fingerprint, the client address and the cooldown -
 *       never passwords, tokens, session ids or request bodies. Individual
 *       failures and rejected attempts are DEBUG only.</li>
 *   <li><b>Clock-driven</b>: time comes from the injected {@link Clock}, so
 *       expiry is deterministic in tests.</li>
 * </ul>
 *
 * <p>Prototype defaults (also documented in application.properties):
 * 5 failures per account, 20 per client address, 300 seconds of cooldown,
 * 10 000 tracked keys. All four are configurable per environment.
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private static final String ACCOUNT_PREFIX = "a:";
    private static final String IP_PREFIX = "i:";
    /** Rate limit for the capacity warning so a flood cannot spam the log. */
    private static final long CAPACITY_LOG_INTERVAL_MS = 60_000;

    private final int accountFailures;
    private final int ipFailures;
    private final long cooldownMillis;
    private final int maxEntries;
    private final Clock clock;

    private final ConcurrentHashMap<String, ArrayDeque<Long>> failures = new ConcurrentHashMap<>();
    private final AtomicLong lastCapacityLogMs = new AtomicLong(0);

    public LoginThrottle(
            @Value("${cpdts.login-throttle.account-failures:5}") int accountFailures,
            @Value("${cpdts.login-throttle.ip-failures:20}") int ipFailures,
            @Value("${cpdts.login-throttle.cooldown-seconds:300}") long cooldownSeconds,
            @Value("${cpdts.login-throttle.max-entries:10000}") int maxEntries,
            Clock clock) {
        if (accountFailures < 1 || ipFailures < 1 || cooldownSeconds < 1 || maxEntries < 1) {
            throw new IllegalArgumentException(
                    "cpdts.login-throttle settings must all be positive");
        }
        this.accountFailures = accountFailures;
        this.ipFailures = ipFailures;
        this.cooldownMillis = cooldownSeconds * 1000;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /**
     * Remaining cooldown for this attempt, empty when it may proceed.
     *
     * <p>Checked BEFORE any credential or account lookup, so the response is
     * identical for unknown and existing accounts (no enumeration oracle).
     */
    public Optional<Duration> cooldownRemaining(String normalizedAccount, String clientIp) {
        long now = clock.millis();
        long accountMs = remainingMs(accountKey(normalizedAccount), accountFailures, now);
        long ipMs = remainingMs(ipKey(clientIp), ipFailures, now);
        long remaining = Math.max(accountMs, ipMs);
        if (remaining <= 0) {
            return Optional.empty();
        }
        if (log.isDebugEnabled()) {
            log.debug("login_throttled_reject account={} ip={} remaining_ms={}",
                    fingerprint(normalizedAccount), clientIp, remaining);
        }
        return Optional.of(Duration.ofMillis(remaining));
    }

    /** Records one failed sign-in against BOTH the account and the client address. */
    public void recordFailure(String normalizedAccount, String clientIp) {
        long now = clock.millis();
        if (log.isDebugEnabled()) {
            log.debug("login_failure account={} ip={}", fingerprint(normalizedAccount), clientIp);
        }
        record(accountKey(normalizedAccount), accountFailures, now, "account",
                fingerprint(normalizedAccount), clientIp);
        record(ipKey(clientIp), ipFailures, now, "ip", "-", clientIp);
    }

    /**
     * Clears both buckets after a successful sign-in: the legitimate user is
     * proven, and prior failures must never keep them (or their office NAT
     * address) in cooldown.
     */
    public void recordSuccess(String normalizedAccount, String clientIp) {
        failures.remove(accountKey(normalizedAccount));
        failures.remove(ipKey(clientIp));
    }

    // ------------------------------------------------------------------
    // Internal mechanics
    // ------------------------------------------------------------------

    private void record(String key, int threshold, long now, String scope, String account,
            String clientIp) {
        boolean exists = failures.containsKey(key);
        if (!exists && !reserveCapacity()) {
            return; // Fail open: untracked new key, existing cooldowns unaffected.
        }
        boolean[] created = { false };
        boolean[] reached = { false };
        ArrayDeque<Long> stored = failures.compute(key, (k, times) -> {
            ArrayDeque<Long> current = times == null ? new ArrayDeque<>() : times;
            if (times == null) {
                created[0] = true;
            }
            prune(current, now);
            if (current.size() >= threshold) {
                // Already cooling down: rejected attempts never extend it.
                return current;
            }
            current.addLast(now);
            if (current.size() == threshold) {
                reached[0] = true;
            }
            return current;
        });
        if (created[0] && failures.size() > maxEntries) {
            // A concurrent insert raced past the cap: roll this new key back.
            failures.remove(key, stored);
            warnCapacity();
            return;
        }
        if (reached[0]) {
            log.warn("login_throttled scope={} account={} ip={} cooldown_seconds={}",
                    scope, account, clientIp, cooldownMillis / 1000);
        }
    }

    private long remainingMs(String key, int threshold, long now) {
        long[] remaining = { 0 };
        failures.computeIfPresent(key, (k, times) -> {
            prune(times, now);
            if (times.isEmpty()) {
                return null; // Stale key: expired on access.
            }
            if (times.size() >= threshold) {
                // Enough failures must age out to fall below the threshold.
                int mustExpire = times.size() - threshold + 1;
                long cutoff = 0;
                int index = 0;
                for (long timestamp : times) {
                    if (index == mustExpire - 1) {
                        cutoff = timestamp;
                        break;
                    }
                    index++;
                }
                remaining[0] = Math.max(1, cutoff + cooldownMillis - now);
            }
            return times;
        });
        return remaining[0];
    }

    private boolean reserveCapacity() {
        if (failures.size() < maxEntries) {
            return true;
        }
        sweepExpired();
        return failures.size() < maxEntries;
    }

    private void sweepExpired() {
        long now = clock.millis();
        for (String key : failures.keySet()) {
            failures.computeIfPresent(key, (k, times) -> {
                prune(times, now);
                return times.isEmpty() ? null : times;
            });
        }
    }

    private void prune(ArrayDeque<Long> times, long now) {
        // A timestamp ages out once its age reaches the full cooldown window.
        long cutoff = now - cooldownMillis;
        while (!times.isEmpty() && times.getFirst() <= cutoff) {
            times.removeFirst();
        }
    }

    private void warnCapacity() {
        long now = clock.millis();
        long last = lastCapacityLogMs.get();
        if (now - last >= CAPACITY_LOG_INTERVAL_MS && lastCapacityLogMs.compareAndSet(last, now)) {
            log.warn("login_throttle_capacity_reached max_entries={} "
                    + "(fail-open: new keys are not tracked until capacity frees)", maxEntries);
        }
    }

    /** 12 hex chars of SHA-256: enough to correlate abuse, not to identify a person. */
    static String fingerprint(String normalizedAccount) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedAccount.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; degrade without failing the login path.
            return "unavailable";
        }
    }

    private static String accountKey(String normalizedAccount) {
        return ACCOUNT_PREFIX + normalizedAccount;
    }

    private static String ipKey(String clientIp) {
        return IP_PREFIX + (clientIp == null ? "" : clientIp);
    }

    /** Test/diagnostic accessor: number of tracked keys (bounded by max-entries). */
    public int trackedKeys() {
        return failures.size();
    }

    /** Test/diagnostic accessor: recorded failures for one account key. */
    public int recordedFailuresForAccount(String normalizedAccount) {
        ArrayDeque<Long> times = failures.get(accountKey(normalizedAccount));
        return times == null ? 0 : times.size();
    }
}
