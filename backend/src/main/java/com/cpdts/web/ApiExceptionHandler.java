package com.cpdts.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 *   <li>NotFoundException -&gt; 404 {"error":"not_found"}.</li>
 *   <li>ConflictException -&gt; 409 {"error":"conflict"} (duplicate
 *       membership).</li>
 *   <li>HttpRequestMethodNotSupportedException -&gt; 405 with an Allow header
 *       (unsupported method on a known endpoint; previously fell through to
 *       the catch-all as a 500).</li>
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

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException ex) {
        return error(HttpStatus.CONFLICT, "conflict", ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable() {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "Malformed request body");
    }

    /** Non-numeric path variables (e.g. /companies/abc/users) are client errors. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request",
                "invalid path parameter: " + ex.getName());
    }

    /**
     * Unsupported method on a KNOWN endpoint (e.g. DELETE on a GET+POST route)
     * is a client error, not a server error. The Allow header names the
     * supported methods. Security ordering is untouched: unauthenticated or
     * CSRF-failing requests are still rejected by the filter chain before
     * MVC routing can produce this response.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethodNotAllowed(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        ResponseEntity<Map<String, String>> body = error(HttpStatus.METHOD_NOT_ALLOWED,
                "method_not_allowed", "unsupported method: " + String.valueOf(ex.getMethod()));
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            String allow = ex.getSupportedHttpMethods().stream()
                    .map(HttpMethod::name)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(", "));
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .header(HttpHeaders.ALLOW, allow)
                    .body(body.getBody());
        }
        return body;
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
