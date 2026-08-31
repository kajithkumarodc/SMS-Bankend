package com.smsapp.attendance;

import java.util.Locale;
import java.util.Set;

/**
 * Attendance marks. Kept as constants (not a persisted JPA enum) so the column
 * stays a plain string like the other tenant tables; the DB CHECK constraint in
 * migration V6 is the source of truth for the set.
 */
public final class AttendanceStatus {

    public static final String PRESENT = "PRESENT";
    public static final String ABSENT = "ABSENT";
    public static final String LATE = "LATE";

    private static final Set<String> ALL = Set.of(PRESENT, ABSENT, LATE);

    private AttendanceStatus() {
    }

    /** Upper-cases and validates against the allowed set; null/blank/unknown returns null. */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(normalized) ? normalized : null;
    }
}
