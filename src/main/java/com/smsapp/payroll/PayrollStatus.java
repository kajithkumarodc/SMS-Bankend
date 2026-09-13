package com.smsapp.payroll;

/**
 * Lifecycle states for a payroll record. Kept as constants (not a persisted JPA
 * enum) so the column stays a plain string like the other tenant tables, while
 * the DB CHECK constraint in migration V17 is the source of truth for the set.
 */
public final class PayrollStatus {

    public static final String PENDING = "PENDING";
    public static final String PAID = "PAID";

    private PayrollStatus() {
    }
}
