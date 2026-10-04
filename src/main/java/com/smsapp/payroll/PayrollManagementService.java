package com.smsapp.payroll;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.payroll.PayrollManagementDtos.AttendanceMonth;
import com.smsapp.payroll.PayrollManagementDtos.AttendancePay;
import com.smsapp.payroll.PayrollManagementDtos.Line;
import com.smsapp.payroll.PayrollManagementDtos.LineRequest;
import com.smsapp.payroll.PayrollManagementDtos.PayRequest;
import com.smsapp.payroll.PayrollManagementDtos.Payment;
import com.smsapp.payroll.PayrollManagementDtos.PayrollDetail;
import com.smsapp.payroll.PayrollManagementDtos.PayrollRow;
import com.smsapp.payroll.PayrollManagementDtos.RoleOption;
import com.smsapp.payroll.PayrollManagementDtos.StaffInfo;
import com.smsapp.payroll.PayrollManagementDtos.UpdateRequest;
import com.smsapp.school.SchoolRepository;
import com.smsapp.staff.LeaveRequest;
import com.smsapp.staff.LeaveRequestRepository;
import com.smsapp.staff.LeaveRequestStatus;
import com.smsapp.staff.StaffAttendance;
import com.smsapp.staff.StaffAttendanceRepository;
import com.smsapp.staff.StaffDirectoryDtos.StaffCardResponse;
import com.smsapp.staff.StaffDirectoryDtos.StaffMemberResponse;
import com.smsapp.staff.StaffDirectoryService;
import com.smsapp.staff.StaffProfile;
import com.smsapp.staff.StaffProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Human Resource > Payroll: the monthly payroll of the active staff of a role -- generate it, edit its earnings,
 * deductions and tax, pay it, or revert it. {@code net = basic + earnings - deductions - tax}. A generated payroll
 * (status PENDING) can be edited; a paid one is locked until it is reverted.
 */
@Service
public class PayrollManagementService {

    public static final String NOT_GENERATED = "NOT_GENERATED";
    public static final String GENERATED = "GENERATED";
    public static final String PAID = "PAID";

    /** The Edit Payroll page shows this many months of attendance, ending at the payroll's month. */
    private static final int ATTENDANCE_MONTHS = 3;

    /** Name of the deduction line that holds the loss of pay; Calculate on the Edit Payroll page replaces it. */
    public static final String LOSS_OF_PAY = "Loss of pay";

    private final PayrollRecordRepository recordRepository;
    private final PayrollItemRepository itemRepository;
    private final StaffProfileRepository profileRepository;
    private final StaffDirectoryService directoryService;
    private final StaffAttendanceRepository attendanceRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final SchoolRepository schoolRepository;
    private final AuditService auditService;

    public PayrollManagementService(PayrollRecordRepository recordRepository, PayrollItemRepository itemRepository,
                                    StaffProfileRepository profileRepository, StaffDirectoryService directoryService,
                                    StaffAttendanceRepository attendanceRepository,
                                    LeaveRequestRepository leaveRequestRepository, SchoolRepository schoolRepository,
                                    AuditService auditService) {
        this.recordRepository = recordRepository;
        this.itemRepository = itemRepository;
        this.profileRepository = profileRepository;
        this.directoryService = directoryService;
        this.attendanceRepository = attendanceRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.schoolRepository = schoolRepository;
        this.auditService = auditService;
    }

    /** Roles that staff can hold -- the page's Role choices. */
    @Transactional(readOnly = true)
    public List<RoleOption> roles() {
        return directoryService.options(true).roles().stream().map(r -> new RoleOption(r.id(), r.name())).toList();
    }

    /** The active staff of {@code roleId}, ordered by Staff ID, with the state of their payroll for the month. */
    @Transactional(readOnly = true)
    public List<PayrollRow> list(UUID roleId, int month, int year) {
        if (month < 1 || month > 12 || year < 2000 || year > 2100) {
            throw new ApiException("Choose a valid month and year", HttpStatus.BAD_REQUEST);
        }
        List<StaffCardResponse> staff = directoryService.list(roleId, null, StaffDirectoryService.ACTIVE);
        Map<UUID, PayrollRecord> records = staff.isEmpty() ? Map.of()
                : recordRepository.findByMonthAndYearAndStaffUserIdIn(month, year,
                        staff.stream().map(StaffCardResponse::userId).toList()).stream()
                .collect(Collectors.toMap(PayrollRecord::getStaffUserId, Function.identity()));
        return staff.stream().map(s -> {
            PayrollRecord record = records.get(s.userId());
            return new PayrollRow(s.id(), s.staffId(), s.fullName(), s.roleName(), s.departmentName(),
                    s.designationName(), s.phone(), record == null ? NOT_GENERATED : statusOf(record),
                    record == null ? null : record.getId(), record == null ? null : record.getNetPay());
        }).toList();
    }

    /**
     * Generates the payroll of one staff member for a month: the basic salary on their profile, less the loss of pay
     * their attendance for the month comes to (if any). No other earnings, deductions or tax yet.
     *
     * @throws ApiException 404 if no such staff member, 400 for an inactive staff member or a future month,
     *         409 if the payroll already exists.
     */
    @Transactional
    public PayrollRecord generate(UUID staffProfileId, int month, int year) {
        StaffProfile profile = requireProfile(staffProfileId);
        if (!StaffDirectoryService.ACTIVE.equals(profile.getStatus())) {
            throw new ApiException(profile.getEmployeeCode() + " is not an active staff member", HttpStatus.BAD_REQUEST);
        }
        if (YearMonth.of(year, month).isAfter(YearMonth.now())) {
            throw new ApiException("Payroll cannot be generated for a future month", HttpStatus.BAD_REQUEST);
        }
        if (recordRepository.existsByStaffUserIdAndMonthAndYear(profile.getUserId(), month, year)) {
            throw alreadyGenerated();
        }
        PayrollRecord record = new PayrollRecord();
        record.setStaffUserId(profile.getUserId());
        record.setMonth(month);
        record.setYear(year);
        record.setBaseSalary(profile.getSalaryAmount() == null ? BigDecimal.ZERO : profile.getSalaryAmount());
        record.setEarnings(BigDecimal.ZERO);
        record.setDeductions(BigDecimal.ZERO);
        record.setTax(BigDecimal.ZERO);
        record.setNetPay(record.getBaseSalary());
        record.setStatus(PayrollStatus.PENDING);
        PayrollRecord saved;
        try {
            saved = recordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            throw alreadyGenerated();
        }
        AttendancePay pay = attendancePay(record.getBaseSalary(), breakdowns(profile, saved).get(0));
        if (pay.lossOfPay().signum() > 0) {
            PayrollItem item = new PayrollItem();
            item.setPayrollRecordId(saved.getId());
            item.setKind(PayrollItem.DEDUCTION);
            item.setType(pay.deductionType());
            item.setAmount(pay.lossOfPay());
            item.setPosition(0);
            itemRepository.save(item);
            saved.setDeductions(pay.lossOfPay());
            saved.setNetPay(saved.getBaseSalary().subtract(pay.lossOfPay()));
            saved = recordRepository.save(saved);
        }
        auditService.log(AuditActions.PAYROLL_RECORD_CREATED, AuditActions.PAYROLL_RECORD, saved.getId(),
                Map.of("staffUserId", saved.getStaffUserId().toString(), "month", month, "year", year,
                        "netPay", saved.getNetPay().toPlainString()));
        return saved;
    }

    /** @throws ApiException 404 if no such payroll. */
    @Transactional(readOnly = true)
    public PayrollDetail detail(UUID id) {
        return toDetail(require(id));
    }

    /**
     * Replaces the earning and deduction lines and the tax, and recalculates the net salary.
     *
     * @throws ApiException 404 if no such payroll, 409 if it is already paid, 400 if the net salary would be
     *         negative.
     */
    @Transactional
    public PayrollDetail update(UUID id, UpdateRequest request) {
        PayrollRecord record = require(id);
        if (PayrollStatus.PAID.equals(record.getStatus())) {
            throw new ApiException("This payroll is already paid. Revert it before editing.", HttpStatus.CONFLICT);
        }
        BigDecimal earnings = total(request.earnings());
        BigDecimal deductions = total(request.deductions());
        BigDecimal net = record.getBaseSalary().add(earnings).subtract(deductions).subtract(request.tax());
        if (net.signum() < 0) {
            throw new ApiException("The deductions and tax are more than the gross salary", HttpStatus.BAD_REQUEST);
        }
        itemRepository.deleteByPayrollRecordId(id);
        itemRepository.flush();
        saveLines(id, PayrollItem.EARNING, request.earnings());
        saveLines(id, PayrollItem.DEDUCTION, request.deductions());
        record.setEarnings(earnings);
        record.setDeductions(deductions);
        record.setTax(request.tax());
        record.setNetPay(net);
        PayrollRecord saved = recordRepository.save(record);
        auditService.log(AuditActions.PAYROLL_RECORD_UPDATED, AuditActions.PAYROLL_RECORD, id,
                Map.of("netPay", saved.getNetPay().toPlainString()));
        return toDetail(saved);
    }

    /**
     * Marks the payroll paid.
     *
     * @throws ApiException 404 if no such payroll, 409 if it is already paid, 400 for a future payment date.
     */
    @Transactional
    public PayrollDetail pay(UUID id, PayRequest request) {
        PayrollRecord record = require(id);
        if (PayrollStatus.PAID.equals(record.getStatus())) {
            throw new ApiException("This payroll is already paid", HttpStatus.CONFLICT);
        }
        if (request.paymentDate().isAfter(LocalDate.now())) {
            throw new ApiException("The payment date cannot be in the future", HttpStatus.BAD_REQUEST);
        }
        PayrollPaymentMode mode = PayrollPaymentMode.parse(request.paymentMode()).orElseThrow();
        record.setStatus(PayrollStatus.PAID);
        record.setPaymentMode(mode.name());
        record.setPaymentDate(request.paymentDate());
        record.setPaymentNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        record.setPaidAt(OffsetDateTime.now());
        PayrollRecord saved = recordRepository.save(record);
        auditService.log(AuditActions.PAYROLL_RECORD_PAID, AuditActions.PAYROLL_RECORD, id,
                Map.of("netPay", saved.getNetPay().toPlainString(), "mode", mode.name()));
        return toDetail(saved);
    }

    /**
     * Undoes a step: a generated payroll is deleted (the staff member goes back to "not generated"); a paid
     * payroll goes back to generated, with its payment details cleared.
     *
     * @throws ApiException 404 if no such payroll, 403 if it is paid and {@code canRevertPaid} is false.
     */
    @Transactional
    public void revert(UUID id, boolean canRevertPaid) {
        PayrollRecord record = require(id);
        boolean paid = PayrollStatus.PAID.equals(record.getStatus());
        if (paid && !canRevertPaid) {
            throw new ApiException("You do not have permission to revert a paid payroll", HttpStatus.FORBIDDEN);
        }
        if (paid) {
            record.setStatus(PayrollStatus.PENDING);
            record.setPaymentMode(null);
            record.setPaymentDate(null);
            record.setPaymentNote(null);
            record.setPaidAt(null);
            recordRepository.save(record);
        } else {
            itemRepository.deleteByPayrollRecordId(id);
            recordRepository.delete(record);
        }
        auditService.log(AuditActions.PAYROLL_RECORD_REVERTED, AuditActions.PAYROLL_RECORD, id,
                Map.of("wasPaid", paid, "month", record.getMonth(), "year", record.getYear()));
    }

    // --- Detail mapping ---------------------------------------------------------------------------

    private PayrollDetail toDetail(PayrollRecord record) {
        StaffProfile profile = profileRepository.findByUserId(record.getStaffUserId())
                .orElseThrow(() -> new IllegalStateException("Payroll " + record.getId() + " has no staff profile"));
        StaffMemberResponse member = directoryService.toResponse(profile);
        List<PayrollItem> items = itemRepository.findByPayrollRecordIdOrderByPosition(record.getId());
        List<Line> earnings = lines(items, PayrollItem.EARNING);
        List<Line> deductions = lines(items, PayrollItem.DEDUCTION);
        // A payroll from before lines existed has a deduction total but no lines: show it as one line.
        if (deductions.isEmpty() && record.getDeductions().signum() > 0) {
            deductions = List.of(new Line("Deduction", record.getDeductions()));
        }
        BigDecimal gross = record.getBaseSalary().add(record.getEarnings());
        Payment payment = PayrollStatus.PAID.equals(record.getStatus()) ? new Payment(record.getPaymentMode(),
                record.getPaymentMode() == null ? null
                        : PayrollPaymentMode.parse(record.getPaymentMode()).map(PayrollPaymentMode::label).orElse(record.getPaymentMode()),
                record.getPaymentDate(), record.getPaymentNote(), record.getPaidAt()) : null;
        String schoolName = schoolRepository.findAll().stream().findFirst().map(s -> s.getName()).orElse(null);
        StaffInfo staff = new StaffInfo(profile.getId(), member.staffId(), member.fullName(), member.phone(),
                member.email(), member.epfNo(), member.roleName(), member.departmentName(), member.designationName(),
                member.hasPhoto(), member.createdAt());
        List<PayrollAttendance> breakdowns = breakdowns(profile, record);
        List<AttendanceMonth> attendance = attendance(profile, record, breakdowns);
        return new PayrollDetail(record.getId(), record.getMonth(), record.getYear(), statusOf(record), staff,
                attendance, record.getBaseSalary(), earnings, deductions, record.getEarnings(),
                record.getDeductions(), gross, record.getTax(), record.getNetPay(), payment, schoolName,
                attendancePay(record.getBaseSalary(), breakdowns.get(0)));
    }

    /** How the days of the payroll's month and the two before it count toward pay, newest first. */
    private List<PayrollAttendance> breakdowns(StaffProfile profile, PayrollRecord record) {
        List<LeaveRequest> approved = leaveRequestRepository
                .findByStaffUserIdAndStatusOrderByCreatedAtDesc(profile.getUserId(), LeaveRequestStatus.APPROVED);
        LocalDate today = LocalDate.now();
        List<PayrollAttendance> months = new ArrayList<>();
        YearMonth month = YearMonth.of(record.getYear(), record.getMonth());
        for (int i = 0; i < ATTENDANCE_MONTHS; i++) {
            YearMonth current = month.minusMonths(i);
            months.add(PayrollAttendance.of(current, attendanceRepository
                    .findByStaffProfileIdAndAttendanceDateBetween(profile.getId(), current.atDay(1), current.atEndOfMonth()),
                    approved, today));
        }
        return months;
    }

    /** Attendance counts for the payroll's month and the two before it, newest first. */
    private List<AttendanceMonth> attendance(StaffProfile profile, PayrollRecord record, List<PayrollAttendance> breakdowns) {
        List<LeaveRequest> approved = leaveRequestRepository
                .findByStaffUserIdAndStatusOrderByCreatedAtDesc(profile.getUserId(), LeaveRequestStatus.APPROVED);
        List<AttendanceMonth> months = new ArrayList<>();
        YearMonth month = YearMonth.of(record.getYear(), record.getMonth());
        for (int i = 0; i < ATTENDANCE_MONTHS; i++) {
            YearMonth current = month.minusMonths(i);
            Map<String, Long> counts = attendanceRepository
                    .findByStaffProfileIdAndAttendanceDateBetween(profile.getId(), current.atDay(1), current.atEndOfMonth())
                    .stream().collect(Collectors.groupingBy(StaffAttendance::getStatus, Collectors.counting()));
            months.add(new AttendanceMonth(current.getMonthValue(), current.getYear(), count(counts, "PRESENT"),
                    count(counts, "LATE"), count(counts, "ABSENT"), count(counts, "HALF_DAY"), count(counts, "HOLIDAY"),
                    count(counts, "HALF_DAY_SECOND_HALF"), leaveDays(approved, current), breakdowns.get(i).unmarked()));
        }
        return months;
    }

    /** What one month of attendance is worth in salary; see {@link AttendancePay}. */
    static AttendancePay attendancePay(BigDecimal basic, PayrollAttendance a) {
        BigDecimal days = BigDecimal.valueOf(a.daysInMonth());
        BigDecimal leave = a.leaveDays();
        BigDecimal payable = a.payableDays();
        BigDecimal notPayable = days.subtract(payable); // leave days and upcoming days
        BigDecimal perDay = basic.divide(days, 2, java.math.RoundingMode.HALF_UP);
        BigDecimal loss = basic.multiply(notPayable).divide(days, 2, java.math.RoundingMode.HALF_UP).min(basic);
        return new AttendancePay(a.daysInMonth(), a.presentDays(), leave, a.absent(), a.unmarked(), a.halfDays(),
                a.holidays() + a.weeklyOffs(), a.paidLeave(), a.upcoming(), payable, perDay, basic.subtract(loss), loss,
                lossLabel(leave, a.upcoming()));
    }

    /** "Loss of pay (2 days)", "Loss of pay (2 leave + 28 upcoming days)" or "Loss of pay (28 upcoming days)". */
    private static String lossLabel(BigDecimal leave, int upcoming) {
        String leaveText = leave.stripTrailingZeros().toPlainString();
        if (upcoming == 0) {
            return LOSS_OF_PAY + " (" + leaveText + (leave.compareTo(BigDecimal.ONE) == 0 ? " day)" : " days)");
        }
        String upcomingText = upcoming + " upcoming " + (upcoming == 1 ? "day" : "days");
        return LOSS_OF_PAY + " (" + (leave.signum() == 0 ? upcomingText : leaveText + " leave + " + upcomingText) + ")";
    }

    private static int count(Map<String, Long> counts, String status) {
        return counts.getOrDefault(status, 0L).intValue();
    }

    /** Days of {@code month} covered by approved leave. */
    private static int leaveDays(List<LeaveRequest> approved, YearMonth month) {
        int days = 0;
        for (LeaveRequest leave : approved) {
            LocalDate from = leave.getStartDate().isAfter(month.atDay(1)) ? leave.getStartDate() : month.atDay(1);
            LocalDate to = leave.getEndDate().isBefore(month.atEndOfMonth()) ? leave.getEndDate() : month.atEndOfMonth();
            if (!to.isBefore(from)) {
                days += (int) (to.toEpochDay() - from.toEpochDay()) + 1;
            }
        }
        return days;
    }

    private static List<Line> lines(List<PayrollItem> items, String kind) {
        return items.stream().filter(i -> kind.equals(i.getKind())).map(i -> new Line(i.getType(), i.getAmount())).toList();
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private void saveLines(UUID recordId, String kind, List<LineRequest> lines) {
        for (int i = 0; i < lines.size(); i++) {
            PayrollItem item = new PayrollItem();
            item.setPayrollRecordId(recordId);
            item.setKind(kind);
            item.setType(lines.get(i).type().trim());
            item.setAmount(lines.get(i).amount());
            item.setPosition(i);
            itemRepository.save(item);
        }
    }

    private static BigDecimal total(List<LineRequest> lines) {
        return lines.stream().map(LineRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String statusOf(PayrollRecord record) {
        return PayrollStatus.PAID.equals(record.getStatus()) ? PAID : GENERATED;
    }

    private PayrollRecord require(UUID id) {
        return recordRepository.findById(id).orElseThrow(() -> new ApiException("Payroll not found", HttpStatus.NOT_FOUND));
    }

    private StaffProfile requireProfile(UUID id) {
        return profileRepository.findById(id).orElseThrow(() -> new ApiException("Staff member not found", HttpStatus.NOT_FOUND));
    }

    private static ApiException alreadyGenerated() {
        return new ApiException("The payroll for this staff member and month has already been generated", HttpStatus.CONFLICT);
    }
}
