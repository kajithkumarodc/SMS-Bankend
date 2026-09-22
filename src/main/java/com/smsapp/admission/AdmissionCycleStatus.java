package com.smsapp.admission;

import java.util.Set;

/** Admission cycle lifecycle. The DB CHECK constraint (V27) is the source of truth for the set. */
public final class AdmissionCycleStatus {

    public static final String DRAFT = "DRAFT";
    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    private static final Set<String> ALL = Set.of(DRAFT, OPEN, CLOSED);

    private AdmissionCycleStatus() {
    }

    public static boolean isValid(String raw) {
        return ALL.contains(raw);
    }
}
