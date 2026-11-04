package com.cpdts.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;

import com.cpdts.CpdtsApplication;

/** Isolated browser-test server; refuses all databases except local cpdts_test. */
public class BrowserTestServer {
    public static void main(String[] args) {
        assertSafeTarget(System.getenv("CPDTS_DB_URL"), System.getenv("CPDTS_E2E_NONCE"),
                System.getenv("SPRING_APPLICATION_JSON"), System.getProperty("spring.application.json"), args);
        System.setProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/cpdts_test");
        System.setProperty("spring.flyway.enabled", "false");
        System.setProperty("server.port", "18081");
        new SpringApplication(CpdtsApplication.class, Guard.class).run();
    }

    static void assertSafeTarget(String url, String nonce, String jsonEnv, String jsonProperty, String[] args) {
        if (args.length != 0 || !"jdbc:postgresql://localhost:5432/cpdts_test".equals(url)
                || jsonEnv != null || jsonProperty != null || nonce == null || nonce.isBlank()) {
            throw new IllegalStateException("Browser tests require the exact local cpdts_test JDBC URL");
        }
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
            return () -> {
                try (Connection connection = dataSource.getConnection();
                        Statement statement = connection.createStatement();
                        ResultSet result = statement.executeQuery("SELECT pg_catalog.current_database()")) {
                    if (!result.next() || !"cpdts_test".equals(result.getString(1))) {
                        throw new IllegalStateException("Browser tests refuse non-test databases");
                    }
                }
            };
        }
    }
}
