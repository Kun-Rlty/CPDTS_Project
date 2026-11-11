package com.cpdts.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Controllable clock for tests that must prove time-based behaviour
 * (login-throttle cooldown expiry) without sleeping.
 *
 * <p>Starts at a fixed instant; tests advance it explicitly with
 * {@link #advance(Duration)}. Zone is fixed UTC - the login throttle only
 * reads {@link #millis()}, never zone-dependent business dates.
 */
public final class MutableClock extends Clock {

    private volatile Instant current;

    public MutableClock(Instant start) {
        this.current = start;
    }

    public void advance(Duration duration) {
        current = current.plus(duration);
    }

    public void set(Instant instant) {
        this.current = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return current;
    }
}
