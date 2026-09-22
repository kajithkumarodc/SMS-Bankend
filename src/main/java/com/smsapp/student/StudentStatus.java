package com.smsapp.student;

import java.util.Locale;
import java.util.Set;

/**
 * Lifecycle states for a student record. Kept as constants (not a persisted JPA
 * enum) so the column stays a plain string like the other status columns, while
 * the DB CHECK constraint in migration V5 is the source of truth for the set.
 */
public final class StudentStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    public static final String GRADUATED = "GRADUATED";
    public static final String LEFT_SCHOOL = "LEFT_SCHOOL";
    public static final String TRANSFERRED = "TRANSFERRED";

    private static final Set<String> ALL = Set.of(ACTIVE, INACTIVE, GRADUATED, LEFT_SCHOOL, TRANSFERRED);

    public static final String VALID_VALUES_MESSAGE = "Status must be one of ACTIVE, INACTIVE, GRADUATED, LEFT_SCHOOL, TRANSFERRED";

    private StudentStatus() {
    }

    /** Normalises to upper-case and validates against the allowed set; null/blank/unknown returns null. */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(normalized) ? normalized : null;
    }
}
