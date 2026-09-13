package com.smsapp.staff;

import java.util.Locale;
import java.util.Set;

/**
 * Lifecycle states for a staff profile. Kept as constants (not a persisted JPA
 * enum) so the column stays a plain string like the other tenant tables, while
 * the DB CHECK constraint in migration V17 is the source of truth for the set.
 */
public final class StaffProfileStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    private static final Set<String> ALL = Set.of(ACTIVE, INACTIVE);

    private StaffProfileStatus() {
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
