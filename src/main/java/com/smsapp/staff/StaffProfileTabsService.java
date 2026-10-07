package com.smsapp.staff;

import com.smsapp.payroll.PayrollPaymentMode;
import com.smsapp.payroll.PayrollRecord;
import com.smsapp.payroll.PayrollRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Payroll and Attendance tabs of a staff member's profile (Leaves comes from {@link LeaveManagementService}). */
@Service
public class StaffProfileTabsService {

    /** One payslip row: the payroll id is what the payslip modal and Payroll page open. */
    public record PayslipRow(UUID id, int month, int year, LocalDate date, String modeLabel, String status,
                             BigDecimal netSalary) {
    }

    public record PayrollSummary(BigDecimal totalNetPaid, BigDecimal totalGross, BigDecimal totalEarning,
                                 BigDecimal totalDeduction, List<PayslipRow> payslips) {
    }

    /** {@code days} maps "month-day" (e.g. "3-14") to a status code of {@link StaffAttendanceStatus}. */
    public record AttendanceSummary(int year, int present, int late, int absent, int halfDay, int holiday,
                                    int halfDaySecondHalf, Map<String, String> days) {
    }

    private final StaffDirectoryService directoryService;
    private final PayrollRecordRepository payrollRepository;
    private final StaffAttendanceRepository attendanceRepository;

    public StaffProfileTabsService(StaffDirectoryService directoryService, PayrollRecordRepository payrollRepository,
                                   StaffAttendanceRepository attendanceRepository) {
        this.directoryService = directoryService;
        this.payrollRepository = payrollRepository;
        this.attendanceRepository = attendanceRepository;
    }

    /** The staff member's payslips, newest first. Totals count only paid payrolls. */
    @Transactional(readOnly = true)
    public PayrollSummary payroll(UUID staffProfileId) {
        StaffProfile profile = directoryService.require(staffProfileId);
        List<PayrollRecord> records = payrollRepository.findByStaffUserIdOrderByYearDescMonthDesc(profile.getUserId());
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal earning = BigDecimal.ZERO;
        BigDecimal deduction = BigDecimal.ZERO;
        List<PayslipRow> rows = new ArrayList<>();
        for (PayrollRecord r : records) {
            boolean paid = "PAID".equals(r.getStatus());
            if (paid) {
                net = net.add(r.getNetPay());
                gross = gross.add(r.getBaseSalary().add(r.getEarnings()));
                earning = earning.add(r.getEarnings());
                deduction = deduction.add(r.getDeductions()).add(r.getTax());
            }
            String mode = PayrollPaymentMode.parse(r.getPaymentMode()).map(PayrollPaymentMode::label).orElse(null);
            LocalDate date = r.getPaymentDate() != null ? r.getPaymentDate()
                    : r.getCreatedAt() == null ? null : r.getCreatedAt().toLocalDate();
            rows.add(new PayslipRow(r.getId(), r.getMonth(), r.getYear(), date, mode, r.getStatus(), r.getNetPay()));
        }
        return new PayrollSummary(net, gross, earning, deduction, rows);
    }

    /** The year's attendance marks of a staff member, with the counts of each kind. */
    @Transactional(readOnly = true)
    public AttendanceSummary attendance(UUID staffProfileId, int year) {
        directoryService.require(staffProfileId);
        Map<String, String> days = new LinkedHashMap<>();
        int present = 0;
        int late = 0;
        int absent = 0;
        int half = 0;
        int holiday = 0;
        int second = 0;
        for (StaffAttendance a : attendanceRepository.findByStaffProfileIdAndAttendanceDateBetween(
                staffProfileId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))) {
            days.put(a.getAttendanceDate().getMonthValue() + "-" + a.getAttendanceDate().getDayOfMonth(), a.getStatus());
            switch (a.getStatus()) {
                case "PRESENT" -> present++;
                case "LATE" -> late++;
                case "ABSENT" -> absent++;
                case "HALF_DAY" -> half++;
                case "HOLIDAY" -> holiday++;
                case "HALF_DAY_SECOND_HALF" -> second++;
                default -> { }
            }
        }
        return new AttendanceSummary(year, present, late, absent, half, holiday, second, days);
    }
}
