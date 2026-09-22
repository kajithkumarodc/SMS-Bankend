package com.smsapp.user;

/**
 * Lifecycle states for a user account. Kept as a constant (not a persisted JPA
 * enum) so the column stays a plain string like the other status columns --
 * mirrors {@code com.smsapp.student.StudentStatus}.
 */
public final class UserStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    /** Normalizes to a valid status, or {@code null} if {@code raw} is not one of ACTIVE/INACTIVE. */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return ACTIVE.equals(upper) || INACTIVE.equals(upper) ? upper : null;
    }

    private UserStatus() {
    }
}
