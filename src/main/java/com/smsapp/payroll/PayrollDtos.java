package com.smsapp.payroll;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the payroll API. Entities are never exposed directly (plan section 7.1d). */
final class PayrollDtos {

    private PayrollDtos() {
    }

    /**
     * {@code baseSalary} is deliberately not part of the request -- it is read from the
     * staff member's current profile (see {@link PayrollService#generate}) so a payroll
     * record can't be created against a made-up salary.
     */
    record GeneratePayrollRequest(
            @NotNull UUID staffUserId,
            @NotNull @Min(1) @Max(12) Integer month,
            @NotNull @Min(2000) @Max(2100) Integer year,
            @NotNull @PositiveOrZero BigDecimal deductions) {
    }

    record PayrollRecordResponse(
            UUID id,
            UUID staffUserId,
            int month,
            int year,
            BigDecimal baseSalary,
            BigDecimal deductions,
            BigDecimal netPay,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime paidAt) {

        static PayrollRecordResponse from(PayrollRecord record) {
            return new PayrollRecordResponse(
                    record.getId(),
                    record.getStaffUserId(),
                    record.getMonth(),
                    record.getYear(),
                    record.getBaseSalary(),
                    record.getDeductions(),
                    record.getNetPay(),
                    record.getStatus(),
                    record.getCreatedAt(),
                    record.getPaidAt());
        }
    }
}
