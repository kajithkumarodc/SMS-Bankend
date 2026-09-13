package com.smsapp.staff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the staff API. Entities are never exposed directly (plan section 7.1d). */
final class StaffDtos {

    private StaffDtos() {
    }

    record CreateStaffProfileRequest(
            @NotNull UUID userId,
            @NotBlank @Size(max = 50) String employeeCode,
            @Size(max = 200) String department,
            @Size(max = 200) String designation,
            @NotNull @PastOrPresent LocalDate dateOfJoining,
            @NotNull @PositiveOrZero BigDecimal salaryAmount) {
    }

    /**
     * Editable fields on an existing staff profile. {@code employeeCode} is deliberately
     * absent -- mirrors {@code students.admission_number} (see {@link StaffService#update}).
     */
    record UpdateStaffProfileRequest(
            @Size(max = 200) String department,
            @Size(max = 200) String designation,
            @NotNull @PastOrPresent LocalDate dateOfJoining,
            @NotNull @PositiveOrZero BigDecimal salaryAmount,
            @NotBlank String status) {
    }

    record StaffProfileResponse(
            UUID id,
            UUID userId,
            String employeeCode,
            String department,
            String designation,
            LocalDate dateOfJoining,
            BigDecimal salaryAmount,
            String status,
            OffsetDateTime createdAt) {

        static StaffProfileResponse from(StaffProfile profile) {
            return new StaffProfileResponse(
                    profile.getId(),
                    profile.getUserId(),
                    profile.getEmployeeCode(),
                    profile.getDepartment(),
                    profile.getDesignation(),
                    profile.getDateOfJoining(),
                    profile.getSalaryAmount(),
                    profile.getStatus(),
                    profile.getCreatedAt());
        }
    }
}
