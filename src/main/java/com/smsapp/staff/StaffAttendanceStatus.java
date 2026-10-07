package com.smsapp.staff;

import java.util.Locale;
import java.util.Optional;

/** The marks on the Staff Attendance page, in display order. */
public enum StaffAttendanceStatus {
    PRESENT, LATE, ABSENT, HALF_DAY, HOLIDAY, HALF_DAY_SECOND_HALF;

    public static Optional<StaffAttendanceStatus> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
