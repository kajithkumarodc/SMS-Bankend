package com.smsapp.fee;

import java.util.Set;

/** Fee structure billing frequency; the DB CHECK constraint (V26) is the source of truth for the set. */
public final class FeeFrequency {

    public static final String ONE_TIME = "ONE_TIME";
    public static final String MONTHLY = "MONTHLY";
    public static final String QUARTERLY = "QUARTERLY";
    public static final String HALF_YEARLY = "HALF_YEARLY";
    public static final String ANNUAL = "ANNUAL";

    private static final Set<String> ALL = Set.of(ONE_TIME, MONTHLY, QUARTERLY, HALF_YEARLY, ANNUAL);

    private FeeFrequency() {
    }

    public static boolean isValid(String raw) {
        return ALL.contains(raw);
    }
}
