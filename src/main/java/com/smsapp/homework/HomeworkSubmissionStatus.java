package com.smsapp.homework;

import java.util.Locale;
import java.util.Set;

/**
 * What a student did about a piece of homework. Kept as constants rather than a
 * persisted JPA enum so the column stays a plain string like every other status
 * column here (see {@code com.smsapp.attendance.AttendanceStatus}); the CHECK
 * constraint in migration V29 is the source of truth for the set.
 *
 * <p>{@link #PENDING} is also the implied state of a student with no submission
 * row at all, which is why setting homework does not write one row per student.
 */
public final class HomeworkSubmissionStatus {

    /** Not yet handed in, and not yet overdue. */
    public static final String PENDING = "PENDING";

    /** Handed in on or before the due date. */
    public static final String SUBMITTED = "SUBMITTED";

    /** Handed in, but after the due date. */
    public static final String LATE = "LATE";

    /** The due date passed and the teacher recorded it as never handed in. */
    public static final String NOT_SUBMITTED = "NOT_SUBMITTED";

    private static final Set<String> ALL = Set.of(PENDING, SUBMITTED, LATE, NOT_SUBMITTED);

    private HomeworkSubmissionStatus() {
    }

    /** Upper-cases and validates against the allowed set; null/blank/unknown returns null. */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(normalized) ? normalized : null;
    }

    /** True for the two statuses that mean the work was actually handed in. */
    public static boolean isHandedIn(String status) {
        return SUBMITTED.equals(status) || LATE.equals(status);
    }
}
