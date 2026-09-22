package com.smsapp.fee;

import java.util.Set;

/** Fee structure lifecycle; the DB CHECK constraint (V26) is the source of truth for the set. */
public final class FeeStructureStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    private static final Set<String> ALL = Set.of(ACTIVE, INACTIVE);

    private FeeStructureStatus() {
    }

    public static boolean isValid(String raw) {
        return ALL.contains(raw);
    }
}
