package com.cpdts.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application clock.
 *
 * <p>Every business-date decision (invoice overdue derivation, date-only
 * {@code receivedAt} conversion, dashboard/report date bases) reads time
 * through ONE injected {@link Clock} instead of {@code LocalDate.now()} /
 * {@code Instant.now()} with the JVM default zone. That makes the business
 * timezone explicit and lets tests pin date boundaries deterministically
 * ({@code @MockitoBean Clock}).
 *
 * <p>The clock's ZONE IS the business timezone: services convert date-only
 * input and derive business dates with {@code clock.getZone()} /
 * {@code LocalDate.now(clock)}, so this bean is the single source of truth
 * for the business-date rule (Africa/Dar_es_Salaam, UTC+3, no DST).
 */
@Configuration
public class ClockConfig {

    /** Business timezone for business dates, overdue and date-only input. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
