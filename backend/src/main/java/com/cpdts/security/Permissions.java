package com.cpdts.security;

import java.util.Set;

/**
 * The single source of truth for permission codes in Java.
 *
 * <p>These codes must stay in sync with the catalogue seeded by
 * {@code V6__seed_roles_and_permissions.sql}; the automated permission-matrix
 * test asserts the database matches this class exactly.
 */
public final class Permissions {

    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String CUSTOMER_MANAGE = "CUSTOMER_MANAGE";
    public static final String INVOICE_MANAGE = "INVOICE_MANAGE";
    public static final String PAYMENT_RECORD = "PAYMENT_RECORD";
    public static final String PAYMENT_VERIFY = "PAYMENT_VERIFY";
    public static final String RECORD_VIEW = "RECORD_VIEW";
    public static final String REPORT_VIEW = "REPORT_VIEW";

    /** Every permission currently defined (platform-admin bypass set). */
    public static final Set<String> ALL = Set.of(
            USER_MANAGE,
            CUSTOMER_MANAGE,
            INVOICE_MANAGE,
            PAYMENT_RECORD,
            PAYMENT_VERIFY,
            RECORD_VIEW,
            REPORT_VIEW);

    private Permissions() {
    }
}
