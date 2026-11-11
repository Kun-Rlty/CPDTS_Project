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
        System.setProperty("server.port", Integer.toString(backendPort(System.getenv("CPDTS_BACKEND_PORT"))));
        // Documented prototype login-throttle defaults. The test classpath's
        // application.properties raises thresholds for the cpdts_test suites
        // (shared 127.0.0.1 bucket); the guarded browser run must exercise the
        // SAME defaults documented for operators. Keep in sync with
        // src/main/resources/application.properties.
        System.setProperty("cpdts.login-throttle.account-failures", "5");
        System.setProperty("cpdts.login-throttle.ip-failures", "20");
        System.setProperty("cpdts.login-throttle.cooldown-seconds", "300");
        System.setProperty("cpdts.login-throttle.max-entries", "10000");
        // The guarded test server is reached directly or through the local HTTPS
        // proxy. It must never listen on a non-loopback interface.
        System.setProperty("server.address", "127.0.0.1");
        new SpringApplication(CpdtsApplication.class, Guard.class).run();
    }

    /** Validates the optional test-server port used by the HTTPS proxy run. */
    static int backendPort(String value) {
        if (value == null || value.isBlank()) {
            return 18081;
        }
        try {
            int port = Integer.parseInt(value.trim());
            if (port >= 1024 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the fail-closed error below.
        }
        throw new IllegalStateException("CPDTS_BACKEND_PORT must be a port between 1024 and 65535");
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
