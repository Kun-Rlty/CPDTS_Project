package com.cpdts.bootstrap;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * One-time platform-admin bootstrap COMMAND (thin wrapper around
 * {@link PlatformAdminBootstrapper}).
 *
 * <p>The password is read PRIVATELY from the console (no echo) and is never
 * accepted as a command-line argument, environment variable, file or log
 * entry. The password value is never printed by this program.
 *
 * <p>When no interactive console is attached (piped/CI input), the command
 * REFUSES to read a password unless the operator explicitly passes
 * {@code --allow-visible-input}, because input could then be echoed on
 * screen or captured in shell history.
 *
 * <p>How to run (from the repository root, after the databases are migrated):
 *
 * <pre>
 *   # set CPDTS_DB_URL / CPDTS_DB_USER / CPDTS_DB_PASSWORD from .env.backend,
 *   # then, from an INTERACTIVE terminal:
 *   mvn -f backend\pom.xml spring-boot:run "-Dspring-boot.run.main-class=com.cpdts.bootstrap.PlatformAdminBootstrap"
 * </pre>
 *
 * <p>Exit codes: 0 = created, 1 = refused (safe refusal message, no changes),
 * 2 = unexpected failure.
 */
public final class PlatformAdminBootstrap {

    /** Explicit opt-in for environments without a private console. */
    static final String ALLOW_VISIBLE_INPUT_FLAG = "--allow-visible-input";

    private PlatformAdminBootstrap() {
    }

    public static void main(String[] args) {
        String url = System.getenv("CPDTS_DB_URL");
        String dbUser = System.getenv("CPDTS_DB_USER");
        String dbPassword = System.getenv("CPDTS_DB_PASSWORD");
        if (isBlank(url) || isBlank(dbUser) || dbPassword == null) {
            fail("CPDTS_DB_URL, CPDTS_DB_USER and CPDTS_DB_PASSWORD must be set "
                    + "(from your local .env.backend). Aborting without changes.", 1);
        }

        try {
            DriverManager.setLoginTimeout(10);
        } catch (Exception ignored) {
            // Best effort only.
        }

        try {
            out("");
            out("CPDTS one-time platform administrator bootstrap");

            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in,
                    StandardCharsets.UTF_8));
            Console console = System.console();
            InputReader reader = console == null ? null : readerFor(console);
            BootstrapInput input = readInput(reader, stdin, allowsVisibleInput(args));

            if (isBlank(input.email()) || isBlank(input.fullName())
                    || input.password().length == 0 || input.repeatPassword().length == 0) {
                fail("All fields are required. No changes were made.", 1);
            }
            String password1 = new String(input.password());
            String password2 = new String(input.repeatPassword());
            Arrays.fill(input.password(), '\0');
            Arrays.fill(input.repeatPassword(), '\0');
            if (!password1.equals(password2)) {
                fail("Passwords do not match. No changes were made.", 1);
            }

            PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);
            PlatformAdminBootstrapper bootstrapper = new PlatformAdminBootstrapper(
                    () -> openConnection(url, dbUser, dbPassword), passwordEncoder);

            String createdEmail = bootstrapper.createInitialPlatformAdmin(input.email(),
                    input.fullName(), password1);

            out("");
            out("SUCCESS: platform administrator created: " + createdEmail);
            out("Only a BCrypt hash was stored; the password was not displayed, logged "
                    + "or written to any file. An audit event was recorded atomically "
                    + "with the new record.");
            out("You can now log in through the API once the application is running.");
            System.exit(0);

        } catch (BootstrapException | IllegalArgumentException e) {
            fail(e.getMessage(), 1);
        } catch (Exception e) {
            // Never print the password or full input; message/class only.
            fail("Unexpected failure (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "). No changes were made.", 2);
        }
    }

    /**
     * Reads the bootstrap inputs.
     *
     * <ul>
     *   <li>With an interactive console ({@code reader != null}): email/full
     *       name via {@code readLine}, both password fields via
     *       {@code readPassword} (no echo), with the char arrays wiped after
     *       copying.</li>
     *   <li>Without a console: refuses with instructions unless
     *       {@code allowVisibleInput} is true (explicit
     *       {@code --allow-visible-input} opt-in), because piped input may be
     *       visible on screen or in shell history.</li>
     * </ul>
     *
     * Package-private and reader-injectable so the no-echo behavior is covered
     * by automated tests with a recording {@link InputReader}.
     */
    static BootstrapInput readInput(InputReader reader, BufferedReader stdin,
            boolean allowVisibleInput) throws IOException {
        if (reader != null) {
            out("(the password is read privately and never displayed)");
            String email = reader.readLine("Email: ");
            String fullName = reader.readLine("Full name: ");
            char[] first = reader.readPassword("Password: ");
            char[] second = reader.readPassword("Repeat password: ");
            char[] password = first == null ? new char[0] : first.clone();
            char[] repeat = second == null ? new char[0] : second.clone();
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
            return new BootstrapInput(email, fullName, password, repeat);
        }

        if (!allowVisibleInput) {
            throw new BootstrapException(
                    "no interactive console is attached, so the password cannot be read "
                            + "privately (piped input may be echoed on screen or captured in "
                            + "shell history). Run this command from an interactive terminal, "
                            + "or - only if you accept visible input - pass "
                            + "'" + ALLOW_VISIBLE_INPUT_FLAG + "' explicitly. No changes were made.");
        }
        warn("No interactive console detected: input may be visible on screen or "
                + "in shell history.");
        String email = prompt(stdin, "Email: ");
        String fullName = prompt(stdin, "Full name: ");
        char[] password = prompt(stdin, "Password: ").toCharArray();
        char[] repeat = prompt(stdin, "Repeat password: ").toCharArray();
        return new BootstrapInput(email, fullName, password, repeat);
    }

    /** Wraps the real console; the password path is {@code readPassword} (no echo). */
    static InputReader readerFor(Console console) {
        return new InputReader() {
            @Override
            public String readLine(String prompt) {
                return console.readLine(prompt);
            }

            @Override
            public char[] readPassword(String prompt) {
                return console.readPassword(prompt);
            }
        };
    }

    /**
     * Narrow view of the console APIs the bootstrap needs. Exists so the
     * no-echo password behavior can be asserted by tests (java.io.Console
     * itself is sealed and cannot be faked).
     */
    interface InputReader {
        String readLine(String prompt);

        char[] readPassword(String prompt);
    }

    static boolean allowsVisibleInput(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (ALLOW_VISIBLE_INPUT_FLAG.equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    /** One set of bootstrap inputs; password chars are wiped by the caller. */
    record BootstrapInput(String email, String fullName, char[] password, char[] repeatPassword) {
    }

    private static Connection openConnection(String url, String user, String password) {
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (Exception e) {
            throw new BootstrapException(
                    "cannot connect to the database (check CPDTS_DB_* settings). "
                            + "No changes were made.", e);
        }
    }

    private static String prompt(BufferedReader stdin, String label) throws IOException {
        out(label);
        return stdin.readLine();
    }

    private static void out(String message) {
        System.out.println(message);
    }

    private static void warn(String message) {
        System.err.println("WARNING: " + message);
    }

    private static void fail(String message, int code) {
        System.err.println("ERROR: " + message);
        System.exit(code);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
