package com.smsapp.student;

/** Why a {@link AcademicHistoryRecord} row was written (matches the DB CHECK constraint from V25). */
public final class AcademicChangeReason {

    public static final String ADMISSION = "ADMISSION";
    public static final String MANUAL_ASSIGNMENT = "MANUAL_ASSIGNMENT";
    public static final String PROMOTION = "PROMOTION";

    private AcademicChangeReason() {
    }
}
