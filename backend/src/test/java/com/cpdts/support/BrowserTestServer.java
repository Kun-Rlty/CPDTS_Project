package com.cpdts.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.cpdts.CpdtsApplication;

/**
 * Isolated browser-test server; refuses every database except a fresh disposable
 * local {@code cpdts_lp_<12 hex>} database created by the browser-test runner.
 * Retained development and test databases are deliberately rejected.
 */
public class BrowserTestServer {
    private static final Pattern DISPOSABLE_URL = Pattern.compile(
            "jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):(\\d{1,5})/(cpdts_lp_[0-9a-f]{12})");

    public static void main(String[] args) {
        String url = System.getenv("CPDTS_DB_URL");
        assertSafeTarget(url, System.getenv("CPDTS_E2E_NONCE"),
                System.getenv("SPRING_APPLICATION_JSON"), System.getProperty("spring.application.json"), args);
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.flyway.enabled", "false");
        System.setProperty("server.port", "18081");
        new SpringApplication(CpdtsApplication.class, Guard.class).run();
    }

    static void assertSafeTarget(String url, String nonce, String jsonEnv, String jsonProperty, String[] args) {
        if (args.length != 0 || jsonEnv != null || jsonProperty != null
                || nonce == null || nonce.isBlank() || databaseName(url) == null) {
            throw new IllegalStateException(
                    "Browser tests require a local disposable cpdts_lp_<12 hex> JDBC URL");
        }
    }

    /** Returns the disposable database name, or null for any other target. */
    static String databaseName(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = DISPOSABLE_URL.matcher(url);
        if (!matcher.matches()) {
            return null;
        }
        int port = Integer.parseInt(matcher.group(1));
        return port >= 1 && port <= 65535 ? matcher.group(2) : null;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Guard {
        @Bean
        Filter browserTestMarker() {
            return (request, response, chain) -> {
                ((HttpServletResponse) response).setHeader("X-CPDTS-Browser-Test", System.getenv("CPDTS_E2E_NONCE"));
                chain.doFilter(request, response);
            };
        }

        @Bean
        InitializingBean browserDatabaseGuard(DataSource dataSource) {
            String expectedDatabase = databaseName(System.getenv("CPDTS_DB_URL"));
            return () -> {
                if (expectedDatabase == null) {
                    throw new IllegalStateException("Browser tests refuse an unsafe database target");
                }
                try (Connection connection = dataSource.getConnection();
                        Statement statement = connection.createStatement();
                        ResultSet result = statement.executeQuery("SELECT pg_catalog.current_database()")) {
                    if (!result.next() || !expectedDatabase.equals(result.getString(1))) {
                        throw new IllegalStateException("Browser tests refuse an unexpected database");
                    }
                }
            };
        }
    }
}
