package com.smsapp.staff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the leave-request API. Entities are never exposed directly (plan section 7.1d). */
final class LeaveDtos {

    private LeaveDtos() {
    }

    record CreateLeaveRequestRequest(
            @NotBlank @Size(max = 50) String leaveType,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @Size(max = 1000) String reason) {
    }

    /** Body for {@code PATCH /api/v1/leave-requests/{id}} -- approve or reject. {@code PENDING} is not a valid decision. */
    record DecideLeaveRequestRequest(
            @NotBlank String status) {
    }

    record LeaveRequestResponse(
            UUID id,
            UUID staffUserId,
            String leaveType,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String reason,
            OffsetDateTime createdAt) {

        static LeaveRequestResponse from(LeaveRequest request) {
            return new LeaveRequestResponse(
                    request.getId(),
                    request.getStaffUserId(),
                    request.getLeaveType(),
                    request.getStartDate(),
                    request.getEndDate(),
                    request.getStatus(),
                    request.getReason(),
                    request.getCreatedAt());
        }
    }
}
