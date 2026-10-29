package com.cpdts.web;

/**
 * Conflict with the current resource state (HTTP 409) - e.g. adding a user to
 * a company where they already have a membership (V1: one membership per
 * company and user).
 */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
