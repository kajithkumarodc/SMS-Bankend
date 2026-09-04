package com.smsapp.fee;

import java.util.Set;

/**
 * Invoice lifecycle states. Kept as constants (not a persisted JPA enum) so the
 * column stays a plain string like the other tenant tables; the DB CHECK
 * constraint in migration V12 is the source of truth for the set.
 */
public final class InvoiceStatus {

    /** Raised, not yet paid. */
    public static final String PENDING = "PENDING";
    /** Payment confirmed by a signature-verified Razorpay webhook. */
    public static final String PAID = "PAID";
    /** Payment attempt failed at the gateway. */
    public static final String FAILED = "FAILED";

    private static final Set<String> ALL = Set.of(PENDING, PAID, FAILED);

    private InvoiceStatus() {
    }

    public static boolean isValid(String raw) {
        return ALL.contains(raw);
    }
}
