package com.cpdts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CPDTS backend entry point (minimal foundation phase).
 *
 * <p>On startup Spring Boot connects to PostgreSQL using the CPDTS_DB_URL /
 * CPDTS_DB_USER / CPDTS_DB_PASSWORD environment variables and runs Flyway
 * against the migrations in {@code classpath:db/migration} (V1-V5, the same
 * files the Maven CLI uses). On an already-migrated database this only
 * validates; it never recreates or modifies existing data.
 *
 * <p>Only a health endpoint is exposed in this phase. Authentication and
 * business APIs are later, separately approved phases.
 */
@SpringBootApplication
public class CpdtsApplication {

    public static void main(String[] args) {
        SpringApplication.run(CpdtsApplication.class, args);
    }
}
