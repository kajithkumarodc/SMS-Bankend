package com.smsapp.attendance;

import java.util.Locale;
import java.util.Set;

/**
 * Attendance marks. Kept as constants (not a persisted JPA enum) so the column
 * stays a plain string like the other status columns; the DB CHECK constraint in
 * migrations V6 and V53 are the source of truth for the set.
 */
public final class AttendanceStatus {

    public static final String PRESENT = "PRESENT";
    public static final String ABSENT = "ABSENT";
    public static final String LATE = "LATE";
    public static final String HOLIDAY = "HOLIDAY";
    public static final String HALF_DAY = "HALF_DAY";

    private static final Set<String> ALL = Set.of(PRESENT, ABSENT, LATE, HOLIDAY, HALF_DAY);

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
