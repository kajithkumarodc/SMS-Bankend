package com.smsapp.user;

/**
 * Lifecycle states for a user account. Kept as a constant (not a persisted JPA
 * enum) so the column stays a plain string like the other tenant tables --
 * mirrors {@code com.smsapp.student.StudentStatus}.
 */
public final class UserStatus {

    public static final String ACTIVE = "ACTIVE";

    private UserStatus() {
    }
}
