package com.smsapp.fee;

import java.util.Set;

/**
 * Invoice lifecycle states. Kept as constants (not a persisted JPA enum) so the
 * column stays a plain string like the other status columns; the DB CHECK
 * constraint in migration V12 is the source of truth for the set.
 */
public final class InvoiceStatus {

    /** Raised, not yet paid. */
    public static final String PENDING = "PENDING";
    /** Some, but not all, of the net payable amount has been collected (Phase 5: partial payments). */
    public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
    /** Fully settled -- paidAmount >= netAmount. Confirmed by a signature-verified Razorpay webhook or a manual collection. */
    public static final String PAID = "PAID";
    /** Payment attempt failed at the gateway. */
    public static final String FAILED = "FAILED";

    private static final Set<String> ALL = Set.of(PENDING, PARTIALLY_PAID, PAID, FAILED);

    private InvoiceStatus() {
    }

    public static boolean isValid(String raw) {
        return ALL.contains(raw);
    }
}
