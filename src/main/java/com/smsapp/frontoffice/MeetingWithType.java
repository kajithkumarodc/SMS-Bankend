package com.smsapp.frontoffice;

import java.util.Locale;
import java.util.Set;

/** Whom a visitor met (matches the {@code visitors_meeting_with_check} constraint, V32). */
public final class MeetingWithType {

    public static final String STAFF = "STAFF";
    public static final String STUDENT = "STUDENT";

    private static final Set<String> ALL = Set.of(STAFF, STUDENT);

    /** Normalizes to a valid type, or {@code null} if {@code raw} is not recognized. */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(upper) ? upper : null;
    }

    private MeetingWithType() {
    }
}
