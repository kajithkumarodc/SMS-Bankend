package com.smsapp.payroll;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.payroll.PayrollDtos.GeneratePayrollRequest;
import com.smsapp.staff.StaffProfile;
import com.smsapp.staff.StaffProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A minimal payroll core (plan section 2, HR & payroll): generate one monthly
 * record per staff member, {@code netPay = baseSalary - deductions}. Every read
 * and write is explicitly scoped by {@code tenant_id} on top of the RLS policy.
 */
@Service
public class PayrollService {

    private final PayrollRecordRepository payrollRecordRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final AuditService auditService;

    public PayrollService(PayrollRecordRepository payrollRecordRepository,
                          StaffProfileRepository staffProfileRepository,
                          AuditService auditService) {
        this.payrollRecordRepository = payrollRecordRepository;
        this.staffProfileRepository = staffProfileRepository;
        this.auditService = auditService;
    }

    /**
     * Generates a payroll record for {@code request.staffUserId()}'s month/year.
     * {@code baseSalary} is read from that staff member's current profile so the
     * record always reflects their real, on-file salary.
     *
     * @throws ApiException 404 if the staff member has no profile in the caller's
     *         tenant, 409 if a record already exists for that staff member + month + year.
     */
    @Transactional
    public PayrollRecord generate(UUID tenantId, GeneratePayrollRequest request) {
        StaffProfile profile = staffProfileRepository.findByTenantIdAndUserId(tenantId, request.staffUserId())
                .orElseThrow(() -> new ApiException("Staff profile not found", HttpStatus.NOT_FOUND));

        if (payrollRecordRepository.existsByTenantIdAndStaffUserIdAndMonthAndYear(
                tenantId, request.staffUserId(), request.month(), request.year())) {
            throw payrollConflict(request.staffUserId(), request.month(), request.year());
        }

        BigDecimal baseSalary = profile.getSalaryAmount();
        BigDecimal deductions = request.deductions();
        BigDecimal netPay = baseSalary.subtract(deductions);

        PayrollRecord record = new PayrollRecord();
        record.setTenantId(tenantId);
        record.setStaffUserId(request.staffUserId());
        record.setMonth(request.month());
        record.setYear(request.year());
        record.setBaseSalary(baseSalary);
        record.setDeductions(deductions);
        record.setNetPay(netPay);
        record.setStatus(PayrollStatus.PENDING);

        PayrollRecord saved;
        try {
            saved = payrollRecordRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent insert for the same staff member + month + year.
            throw payrollConflict(request.staffUserId(), request.month(), request.year());
        }

        auditService.log(AuditActions.PAYROLL_RECORD_CREATED, AuditActions.PAYROLL_RECORD, saved.getId(),
                Map.of("staffUserId", saved.getStaffUserId().toString(), "month", saved.getMonth(),
                        "year", saved.getYear(), "netPay", saved.getNetPay()));
        return saved;
    }

    /** A staff member's own payroll history, newest first. */
    @Transactional(readOnly = true)
    public List<PayrollRecord> ownPayroll(UUID tenantId, UUID staffUserId) {
        return payrollRecordRepository.findByTenantIdAndStaffUserIdOrderByYearDescMonthDesc(tenantId, staffUserId);
    }

    private static ApiException payrollConflict(UUID staffUserId, int month, int year) {
        return new ApiException(
                "A payroll record for staff member " + staffUserId + " for " + month + "/" + year + " already exists",
                HttpStatus.CONFLICT);
    }
}
