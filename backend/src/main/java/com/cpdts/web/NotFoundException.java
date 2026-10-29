package com.cpdts.web;

/**
 * Requested resource does not exist in the addressed scope (HTTP 404) - e.g.
 * a membership that does not exist in the company in the path. Authorization
 * is always checked FIRST, so this never discloses anything about companies
 * the caller cannot manage.
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
