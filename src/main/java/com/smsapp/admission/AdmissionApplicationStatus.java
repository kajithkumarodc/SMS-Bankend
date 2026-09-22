package com.smsapp.admission;

import java.util.Map;
import java.util.Set;

/**
 * Application review state machine (plan part 9). The DB CHECK constraint (V27) is the source of
 * truth for the allowed set; {@link #canTransition} is the source of truth for which transitions
 * are legal, enforced server-side in {@link AdmissionApplicationService} -- never an arbitrary
 * status write.
 */
public final class AdmissionApplicationStatus {

    public static final String SUBMITTED = "SUBMITTED";
    public static final String UNDER_REVIEW = "UNDER_REVIEW";
    public static final String WAITLISTED = "WAITLISTED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private static final Set<String> ALL = Set.of(SUBMITTED, UNDER_REVIEW, WAITLISTED, APPROVED, REJECTED);

    /**
     * SUBMITTED -> UNDER_REVIEW -> {APPROVED, REJECTED, WAITLISTED}; WAITLISTED and REJECTED can
     * both explicitly re-enter UNDER_REVIEW (an audited, controlled reopen -- never a silent
     * REJECTED -> APPROVED). APPROVED is terminal: once converted, nothing changes it again.
     */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            SUBMITTED, Set.of(UNDER_REVIEW),
            UNDER_REVIEW, Set.of(APPROVED, REJECTED, WAITLISTED),
            WAITLISTED, Set.of(UNDER_REVIEW),
            REJECTED, Set.of(UNDER_REVIEW),
            APPROVED, Set.of());

    private AdmissionApplicationStatus() {
    }

    public static boolean isValid(String raw) {
        return ALL.contains(raw);
    }

    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
