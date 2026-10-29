package com.cpdts.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps authentication/authorization failures to stable JSON error responses.
 *
 * <p>Rules:
 * <ul>
 *   <li>BadCredentialsException (unknown email OR wrong password - identical
 *       on purpose) -&gt; 401 {"error":"invalid_credentials"}.</li>
 *   <li>DisabledException (correct password, inactive account) -&gt; 403
 *       {"error":"account_disabled"}.</li>
 *   <li>AccessDeniedException -&gt; 403 {"error":"forbidden"}.</li>
 *   <li>IllegalArgumentException / unreadable JSON -&gt; 400.</li>
 *   <li>Anything else -&gt; 500 with no internals leaked; only the exception
 *       type is logged (never request bodies or credentials).</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials() {
        return error(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password");
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Map<String, String>> handleDisabled() {
        return error(HttpStatus.FORBIDDEN, "account_disabled", "Account is disabled");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied() {
        return error(HttpStatus.FORBIDDEN, "forbidden", "Access denied");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable() {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "Malformed request body");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception of type {}", ex.getClass().getName(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code,
            String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}
