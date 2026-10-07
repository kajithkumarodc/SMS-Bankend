package com.smsapp.staff;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the Approve Leave Request API (Human Resource > Approve Leave Request). */
final class LeaveManagementDtos {

    private LeaveManagementDtos() {
    }

    /**
     * Body for {@code POST /api/v1/hr/leave-requests} and {@code PUT /api/v1/hr/leave-requests/{id}} -- the Add
     * Details form. The document is uploaded separately. Status is only honoured for someone who can approve.
     */
    record LeaveBody(
            @NotNull UUID staffProfileId,
            @NotNull UUID leaveTypeId,
            @NotNull LocalDate applyDate,
            @NotNull LocalDate fromDate,
            @NotNull LocalDate toDate,
            @Pattern(regexp = "(?i)FIRST_HALF|SECOND_HALF", message = "must be First Half or Second Half") String halfDay,
            @Size(max = 1000) String reason,
            @Size(max = 2000) String note,
            @Pattern(regexp = "(?i)PENDING|APPROVED|REJECTED", message = "must be Pending, Approved or Disapproved")
            String status) {
    }

    record AttachmentInfo(String fileName, String contentType, long sizeBytes) {
    }

    record LeaveResponse(
            UUID id,
            UUID staffProfileId,
            String staffId,
            String staffName,
            UUID roleId,
            String roleName,
            UUID leaveTypeId,
            String leaveTypeName,
            /** FIRST_HALF, SECOND_HALF or null. */
            String halfDay,
            LocalDate fromDate,
            LocalDate toDate,
            BigDecimal days,
            LocalDate applyDate,
            String reason,
            String note,
            /** PENDING, APPROVED or REJECTED (shown as Disapproved). */
            String status,
            /** Null when no document is attached. */
            AttachmentInfo attachment,
            OffsetDateTime decidedAt,
            OffsetDateTime createdAt,
            /** Who approves it: Principal, Super Admin, or Super Admin / School Admin. */
            String approverLabel,
            /** Whether the signed-in user may set its status, edit it and delete it. */
            boolean canDecide) {
    }

    /** {@code own} is true for the signed-in user themselves (their request is always Pending). */
    /** One leave type with the signed-in staff member's entitlement for the year; null means no limit is set. */
    record LeaveBalance(UUID leaveTypeId, String name, Integer allotted, BigDecimal used, BigDecimal available) {
    }

    /** Who the signed-in user is as a leave applicant: their staff profile, who approves, and their balances. */
    record MyLeaveInfo(UUID staffProfileId, String staffId, String name, String roleName, String approverLabel, int year,
                       List<LeaveBalance> balances) {
    }

    record StaffOption(UUID id, String staffId, String name, boolean own) {
    }

    record Option(UUID id, String name) {
    }

    /** The Role and Leave Type choices on the page. */
    record OptionsResponse(List<Option> roles, List<Option> leaveTypes) {
    }
}
