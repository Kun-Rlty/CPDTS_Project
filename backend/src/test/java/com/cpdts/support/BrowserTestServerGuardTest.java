package com.cpdts.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BrowserTestServerGuardTest {
    @Test
    void rejectsCommandLineOverridesBeforeStarting() {
        assertThatThrownBy(() -> BrowserTestServer.main(new String[] { "--spring.datasource.url=jdbc:postgresql://localhost:5432/cpdts_dev" }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cpdts_lp_");
    }

    @Test
    void acceptsOnlyLocalDisposableDatabases() {
        String valid = "jdbc:postgresql://localhost:55432/cpdts_lp_abcdef123456";
        BrowserTestServer.assertSafeTarget(valid, "nonce", null, null, new String[0]);
        assertThat(BrowserTestServer.databaseName(valid)).isEqualTo("cpdts_lp_abcdef123456");
        assertThat(BrowserTestServer.databaseName(
                "jdbc:postgresql://127.0.0.1:55432/cpdts_lp_abcdef123456"))
                .isEqualTo("cpdts_lp_abcdef123456");
    }

    @Test
    void rejectsRetainedDevelopmentAndTestDatabases() {
        for (String url : new String[] {
                "jdbc:postgresql://localhost:5432/cpdts_dev",
                "jdbc:postgresql://localhost:5432/cpdts_test",
                "jdbc:postgresql://localhost:5432/cpdts",
                "jdbc:postgresql://localhost:5432/other" }) {
            assertThat(BrowserTestServer.databaseName(url)).isNull();
            assertThatThrownBy(() -> BrowserTestServer.assertSafeTarget(url, "nonce", null, null, new String[0]))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsRemoteOrMalformedDisposableTargets() {
        for (String url : new String[] {
                "jdbc:postgresql://remote:5432/cpdts_lp_abcdef123456",
                "jdbc:postgresql://localhost:99999/cpdts_lp_abcdef123456",
                "jdbc:postgresql://localhost:55432/cpdts_lp_short",
                "jdbc:postgresql://localhost:55432/cpdts_lp_ABCDEF123456",
                "jdbc:postgresql://localhost:55432/cpdts_lp_abcdef123456?user=owner" }) {
            assertThat(BrowserTestServer.databaseName(url)).isNull();
            assertThatThrownBy(() -> BrowserTestServer.assertSafeTarget(url, "nonce", null, null, new String[0]))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void acceptsOnlyValidTestServerPorts() {
        assertThat(BrowserTestServer.backendPort(null)).isEqualTo(18081);
        assertThat(BrowserTestServer.backendPort("  ")).isEqualTo(18081);
        assertThat(BrowserTestServer.backendPort("18082")).isEqualTo(18082);
        for (String value : new String[] { "80", "0", "65536", "-1", "not-a-port" }) {
            assertThatThrownBy(() -> BrowserTestServer.backendPort(value))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsConfigurationOverrides() {
        assertThatThrownBy(() -> BrowserTestServer.assertSafeTarget(
                "jdbc:postgresql://localhost:55432/cpdts_lp_abcdef123456", "nonce", "{}", null, new String[0]))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BrowserTestServer.assertSafeTarget(
                "jdbc:postgresql://localhost:55432/cpdts_lp_abcdef123456", "", null, null, new String[0]))
                .isInstanceOf(IllegalStateException.class);
    }
}
