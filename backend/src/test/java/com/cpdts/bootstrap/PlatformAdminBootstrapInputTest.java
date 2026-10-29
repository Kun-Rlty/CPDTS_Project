package com.cpdts.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for the bootstrap COMMAND's input handling: password fields must go
 * through the no-echo {@code readPassword} API, and environments without a
 * private console must be refused unless the operator explicitly opts in.
 */
class PlatformAdminBootstrapInputTest {

    @Test
    void passwordFieldsAreReadThroughTheNoEchoApi() throws IOException {
        RecordingReader reader = new RecordingReader(
                Map.of("Email: ", "admin@example.test", "Full name: ", "Real Admin"),
                List.of("private pw one 1", "private pw one 1"));

        PlatformAdminBootstrap.BootstrapInput input =
                PlatformAdminBootstrap.readInput(reader, null, false);

        assertThat(reader.readLineCalls)
                .as("identity fields use readLine")
                .containsExactly("Email: ", "Full name: ");
        assertThat(reader.readPasswordCalls)
                .as("BOTH password fields must use the no-echo readPassword API")
                .containsExactly("Password: ", "Repeat password: ");
        assertThat(input.email()).isEqualTo("admin@example.test");
        assertThat(input.fullName()).isEqualTo("Real Admin");
        assertThat(new String(input.password())).isEqualTo("private pw one 1");
        assertThat(new String(input.repeatPassword())).isEqualTo("private pw one 1");
    }

    @Test
    void withoutAConsoleTheBootstrapRefusesToReadAPassword() {
        BufferedReader stdin = new BufferedReader(
                new StringReader("admin@example.test\nReal Admin\nexposed pw 1\nexposed pw 1\n"));

        assertThatThrownBy(() -> PlatformAdminBootstrap.readInput(null, stdin, false))
                .as("without a private console the command must refuse by default")
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("cannot be read privately")
                .hasMessageContaining(PlatformAdminBootstrap.ALLOW_VISIBLE_INPUT_FLAG);
    }

    @Test
    void visibleStdinFallbackRequiresTheExplicitOptInFlag() throws IOException {
        BufferedReader stdin = new BufferedReader(
                new StringReader("admin@example.test\nReal Admin\nvisible pw 1\nvisible pw 1\n"));

        PlatformAdminBootstrap.BootstrapInput input =
                PlatformAdminBootstrap.readInput(null, stdin, true);

        assertThat(input.email()).isEqualTo("admin@example.test");
        assertThat(new String(input.password())).isEqualTo("visible pw 1");
    }

    @Test
    void theVisibleInputFlagIsOnlyRecognizedWhenExplicitlyPassed() {
        assertThat(PlatformAdminBootstrap.allowsVisibleInput(null)).isFalse();
        assertThat(PlatformAdminBootstrap.allowsVisibleInput(new String[0])).isFalse();
        assertThat(PlatformAdminBootstrap.allowsVisibleInput(new String[] {"--other"})).isFalse();
        assertThat(PlatformAdminBootstrap.allowsVisibleInput(
                new String[] {"--allow-visible-input"})).isTrue();
        assertThat(PlatformAdminBootstrap.allowsVisibleInput(
                new String[] {"start", "--ALLOW-VISIBLE-INPUT", "end"})).isTrue();
    }

    /** Minimal {@link PlatformAdminBootstrap.InputReader} that records which API was called. */
    private static final class RecordingReader implements PlatformAdminBootstrap.InputReader {

        final List<String> readLineCalls = new ArrayList<>();
        final List<String> readPasswordCalls = new ArrayList<>();

        private final Map<String, String> lineAnswers;
        private final List<String> passwordAnswers;
        private int passwordIndex;

        RecordingReader(Map<String, String> lineAnswers, List<String> passwordAnswers) {
            this.lineAnswers = lineAnswers;
            this.passwordAnswers = passwordAnswers;
        }

        @Override
        public String readLine(String prompt) {
            readLineCalls.add(prompt);
            return lineAnswers.get(prompt);
        }

        @Override
        public char[] readPassword(String prompt) {
            readPasswordCalls.add(prompt);
            return passwordAnswers.get(passwordIndex++).toCharArray();
        }
    }
}
