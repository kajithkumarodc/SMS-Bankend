package com.smsapp.staff;

import java.util.Locale;
import java.util.Set;

/**
 * Lifecycle states for a leave request. Kept as constants (not a persisted JPA
 * enum) so the column stays a plain string like the other tenant tables, while
 * the DB CHECK constraint in migration V17 is the source of truth for the set.
 */
public final class LeaveRequestStatus {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** Statuses a {@code PATCH} may resolve a request to -- {@code PENDING} is initial-only. */
    private static final Set<String> DECIDABLE = Set.of(APPROVED, REJECTED);

    private LeaveRequestStatus() {
    }

    /** Normalises to upper-case and validates against APPROVED/REJECTED; null/blank/unknown returns null. */
    public static String normalizeDecisionOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return DECIDABLE.contains(normalized) ? normalized : null;
    }
}
