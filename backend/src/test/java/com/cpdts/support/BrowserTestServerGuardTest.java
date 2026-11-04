package com.cpdts.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BrowserTestServerGuardTest {
    @Test
    void rejectsCommandLineOverridesBeforeStarting() {
        assertThatThrownBy(() -> BrowserTestServer.main(new String[] { "--spring.datasource.url=jdbc:postgresql://localhost:5432/cpdts_dev" }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cpdts_test");
    }

    @Test
    void rejectsOtherDatabasesAndConfigurationOverrides() {
        for (String url : new String[] { "jdbc:postgresql://localhost:5432/cpdts_dev",
                "jdbc:postgresql://localhost:5432/other", "jdbc:postgresql://remote:5432/cpdts_test" }) {
            assertThatThrownBy(() -> BrowserTestServer.assertSafeTarget(url, "nonce", null, null, new String[0]))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> BrowserTestServer.assertSafeTarget(
                "jdbc:postgresql://localhost:5432/cpdts_test", "nonce", "{}", null, new String[0]))
                .isInstanceOf(IllegalStateException.class);
    }
}
