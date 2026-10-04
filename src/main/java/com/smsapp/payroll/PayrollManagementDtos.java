package com.smsapp.payroll;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the Payroll page API (Human Resource > Payroll). */
final class PayrollManagementDtos {

    private PayrollManagementDtos() {
    }

    /** One staff member in the Staff List, with the state of their payroll for the chosen month. */
    record PayrollRow(
            UUID staffProfileId,
            String staffId,
            String fullName,
            String roleName,
            String departmentName,
            String designationName,
            String phone,
            /** NOT_GENERATED, GENERATED or PAID. */
            String status,
            /** Null until the payroll is generated. */
            UUID payrollId,
            BigDecimal netSalary) {
    }

    record GenerateRequest(
            @NotNull UUID staffProfileId,
            @NotNull @Min(1) @Max(12) Integer month,
            @NotNull @Min(2000) @Max(2100) Integer year) {
    }

    /** One earning or deduction line on the Edit Payroll page. */
    record LineRequest(
            @NotBlank @Size(max = 100) String type,
            @NotNull
            @DecimalMin(value = "0.00", message = "must not be negative")
            @DecimalMax(value = "9999999999.99", message = "is too large")
            @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimal places") BigDecimal amount) {
    }

    /** Body for {@code PUT /api/v1/payroll/{id}}: the earning and deduction lines and the tax. */
    record UpdateRequest(
            @NotNull @Size(max = 20) List<@Valid LineRequest> earnings,
            @NotNull @Size(max = 20) List<@Valid LineRequest> deductions,
            @NotNull
            @DecimalMin(value = "0.00", message = "must not be negative")
            @DecimalMax(value = "9999999999.99", message = "is too large")
            @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimal places") BigDecimal tax) {
    }

    /** Body for {@code POST /api/v1/payroll/{id}/pay}: the Proceed To Pay form. */
    record PayRequest(
            @NotBlank @Pattern(regexp = "(?i)CASH|CHEQUE|BANK_TRANSFER", message = "must be Cash, Cheque or Transfer to Bank Account")
            String paymentMode,
            @NotNull LocalDate paymentDate,
            @Size(max = 500) String note) {
    }

    record Line(String type, BigDecimal amount) {
    }

    /** Who the payroll is for, as shown beside it. */
    record StaffInfo(UUID staffProfileId, String staffId, String fullName, String phone, String email, String epfNo,
                     String roleName, String departmentName, String designationName, boolean hasPhoto,
                     OffsetDateTime photoVersion) {
    }

    /** Attendance counts of one month: P present, L late, A absent, F half day, H holiday, SH second half, V leave, U unmarked. */
    record AttendanceMonth(int month, int year, int present, int late, int absent, int halfDay, int holiday,
                           int halfDaySecondHalf, int leave, int unmarked) {
    }

    record Payment(String mode, String modeLabel, LocalDate date, String note, OffsetDateTime paidAt) {
    }

    /** A payroll with everything the Edit Payroll page and the payslip show. */
    record PayrollDetail(
            UUID id,
            int month,
            int year,
            /** GENERATED or PAID. */
            String status,
            StaffInfo staff,
            List<AttendanceMonth> attendance,
            BigDecimal basicSalary,
            List<Line> earnings,
            List<Line> deductions,
            BigDecimal earningTotal,
            BigDecimal deductionTotal,
            BigDecimal grossSalary,
            BigDecimal tax,
            BigDecimal netSalary,
            /** Null until paid. */
            Payment payment,
            String schoolName,
            /** What the attendance of the payroll month is worth: the loss of pay a Calculate would deduct. */
            AttendancePay attendancePay) {
    }

    /**
     * What the month's attendance is worth. Only days earned so far are paid: present days (half days count half),
     * approved leave, holidays and Sundays with no mark. Not paid: leave days (absent days, unmarked days and half of
     * each half day) and upcoming days (today and later, not finished yet).
     * {@code earnedSalary = basic / daysInMonth * payableDays}, {@code lossOfPay = basic - earnedSalary}.
     */
    record AttendancePay(int daysInMonth, BigDecimal presentDays, BigDecimal leaveDays, int absentDays, int unmarkedDays,
                         int halfDays, int holidayDays, int paidLeaveDays, int upcomingDays, BigDecimal payableDays,
                         BigDecimal perDayRate, BigDecimal earnedSalary, BigDecimal lossOfPay, String deductionType) {
    }

    record RoleOption(UUID id, String name) {
    }
}
