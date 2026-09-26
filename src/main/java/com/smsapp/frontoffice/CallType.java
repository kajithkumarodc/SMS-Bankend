package com.smsapp.frontoffice;

import java.util.Locale;
import java.util.Set;

/** Direction of a logged phone call (matches {@code phone_call_logs_call_type_check}, V33). */
public final class CallType {

    public static final String INCOMING = "INCOMING";
    public static final String OUTGOING = "OUTGOING";

    private static final Set<String> ALL = Set.of(INCOMING, OUTGOING);

    /** Normalizes to a valid type, or {@code null} if {@code raw} is not recognized. */
    public static String normalizeOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(upper) ? upper : null;
    }

    private CallType() {
    }
}
