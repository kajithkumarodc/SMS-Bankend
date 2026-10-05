package com.smsapp.attendance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the attendance API. Entities are never exposed directly (plan section 7.1d). */
final class AttendanceDtos {

    private AttendanceDtos() {
    }

    record MarkAttendanceRequest(
            @NotNull UUID studentId,
            @NotNull LocalDate date,
            @NotBlank String status) {
    }

    record AttendanceResponse(
            UUID id,
            UUID studentId,
            LocalDate date,
            String status,
            UUID markedBy,
            OffsetDateTime createdAt) {

        static AttendanceResponse from(AttendanceRecord entry) {
            return new AttendanceResponse(
                    entry.getId(),
                    entry.getStudentId(),
                    entry.getDate(),
                    entry.getStatus(),
                    entry.getMarkedBy(),
                    entry.getCreatedAt());
        }
    }

    /** One student on the Student Attendance page: who they are and the mark saved for the day, if any. */
    record RosterRow(
            UUID studentId,
            String admissionNumber,
            String rollNumber,
            String fullName,
            /** Null when nothing has been saved for this day. */
            String status,
            LocalDate date,
            String source,
            LocalTime entryTime,
            LocalTime exitTime,
            String note) {
    }

    record BulkEntry(
            @NotNull UUID studentId,
            @NotBlank String status,
            LocalTime entryTime,
            LocalTime exitTime,
            @Size(max = 500) String note) {
    }

    /** Body for {@code PUT /api/v1/attendance/bulk}: the marks to save for one section's day. */
    record BulkRequest(@NotNull UUID sectionId, @NotNull LocalDate date, @NotEmpty @Size(max = 1000) List<@Valid BulkEntry> entries) {
    }

    record BulkResponse(int saved) {
    }
}
