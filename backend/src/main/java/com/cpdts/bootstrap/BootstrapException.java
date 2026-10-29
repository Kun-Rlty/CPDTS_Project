package com.cpdts.bootstrap;

/**
 * Raised when the one-time platform-admin bootstrap cannot (or must not)
 * complete. The message is safe to print: it never contains credentials.
 */
public class BootstrapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BootstrapException(String message) {
        super(message);
    }

    public BootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
