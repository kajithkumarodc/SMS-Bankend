package com.smsapp.student;

/**
 * Lifecycle states for a student record. Kept as constants (not a persisted JPA
 * enum) so the column stays a plain string like the other tenant tables, while
 * the DB CHECK constraint in migration V5 is the source of truth for the set.
 */
public final class StudentStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    private StudentStatus() {
    }
}
