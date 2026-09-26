package com.smsapp.frontoffice;

import java.util.Locale;
import java.util.Set;

/** Fixed enquiry pipeline states (matches the `admission_enquiries.status` CHECK constraint, V31). */
public final class EnquiryStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String WON = "WON";
    public static final String PASSIVE = "PASSIVE";
    public static final String LOST = "LOST";
    public static final String DEAD = "DEAD";

    private static final Set<String> ALL = Set.of(ACTIVE, PASSIVE, WON, LOST, DEAD);

    /** Statuses that mean "no longer an open lead" -- excluded from "follow-ups due" counts. */
    public static final Set<String> CLOSED = Set.of(WON, LOST, DEAD);

    /** Normalizes to a valid status, or {@code null} if {@code raw} is not recognized. */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(upper) ? upper : null;
    }

    private EnquiryStatus() {
    }
}
