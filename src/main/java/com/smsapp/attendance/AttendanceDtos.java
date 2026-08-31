package com.smsapp.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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

        static AttendanceResponse from(AttendanceRecord record) {
            return new AttendanceResponse(
                    record.getId(),
                    record.getStudentId(),
                    record.getDate(),
                    record.getStatus(),
                    record.getMarkedBy(),
                    record.getCreatedAt());
        }
    }
}
