package com.smsapp.staff;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the Staff Attendance API. */
final class StaffAttendanceDtos {

    private StaffAttendanceDtos() {
    }

    /** One staff member on the page: who they are and the mark saved for that day, if any. */
    record RosterRow(
            UUID staffProfileId,
            String staffId,
            String fullName,
            String roleName,
            /** Null when nothing has been saved for this day. */
            String status,
            /** The day the saved mark is for; null when nothing has been saved. */
            LocalDate date,
            String source,
            LocalTime entryTime,
            LocalTime exitTime,
            String note) {
    }

    record AttendanceEntry(
            @NotNull UUID staffProfileId,
            @NotBlank
            @Pattern(regexp = "(?i)PRESENT|LATE|ABSENT|HALF_DAY|HOLIDAY|HALF_DAY_SECOND_HALF",
                    message = "must be Present, Late, Absent, Half Day, Holiday or Half Day (Second Half)") String status,
            LocalTime entryTime,
            LocalTime exitTime,
            @Size(max = 500) String note) {
    }

    /** Body for {@code PUT /api/v1/staff-attendance}: the marks to save for one day. */
    record SaveRequest(@NotNull LocalDate date, @NotEmpty @Size(max = 1000) List<@Valid AttendanceEntry> entries) {
    }

    record SaveResponse(int saved) {
    }
}
