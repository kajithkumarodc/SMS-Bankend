package com.smsapp.payroll;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.payroll.PayrollDtos.GeneratePayrollRequest;
import com.smsapp.staff.StaffProfile;
import com.smsapp.staff.StaffProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayrollServiceTest {

    @Mock
    private PayrollRecordRepository payrollRecordRepository;

    @Mock
    private StaffProfileRepository staffProfileRepository;

    @Mock
    private AuditService auditService;

    private PayrollService service() {
        return new PayrollService(payrollRecordRepository, staffProfileRepository, auditService);
    }

    private final UUID tenantId = UUID.randomUUID();
    private final UUID staffUserId = UUID.randomUUID();

    private StaffProfile profile(BigDecimal salary) {
        StaffProfile profile = new StaffProfile();
        profile.setTenantId(tenantId);
        profile.setUserId(staffUserId);
        profile.setSalaryAmount(salary);
        return profile;
    }

    @Test
    void generateComputesNetPayFromTheProfilesSalaryAndAudits() {
        when(staffProfileRepository.findByTenantIdAndUserId(tenantId, staffUserId))
                .thenReturn(Optional.of(profile(new BigDecimal("50000.00"))));
        when(payrollRecordRepository.existsByTenantIdAndStaffUserIdAndMonthAndYear(tenantId, staffUserId, 3, 2026))
                .thenReturn(false);
        when(payrollRecordRepository.saveAndFlush(any(PayrollRecord.class))).thenAnswer(inv -> {
            PayrollRecord r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        PayrollRecord created = service().generate(tenantId,
                new GeneratePayrollRequest(staffUserId, 3, 2026, new BigDecimal("5000.00")));

        assertThat(created.getBaseSalary()).isEqualByComparingTo("50000.00");
        assertThat(created.getDeductions()).isEqualByComparingTo("5000.00");
        assertThat(created.getNetPay()).isEqualByComparingTo("45000.00");
        assertThat(created.getStatus()).isEqualTo(PayrollStatus.PENDING);
        verify(auditService).log(eq(AuditActions.PAYROLL_RECORD_CREATED), eq(AuditActions.PAYROLL_RECORD),
                any(), anyMap());
    }

    @Test
    void generateWithZeroDeductionsMakesNetPayEqualBaseSalary() {
        when(staffProfileRepository.findByTenantIdAndUserId(tenantId, staffUserId))
                .thenReturn(Optional.of(profile(new BigDecimal("30000.00"))));
        when(payrollRecordRepository.saveAndFlush(any(PayrollRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        PayrollRecord created = service().generate(tenantId,
                new GeneratePayrollRequest(staffUserId, 1, 2026, BigDecimal.ZERO));

        assertThat(created.getNetPay()).isEqualByComparingTo("30000.00");
    }

    @Test
    void generateForAStaffMemberWithNoProfileReturns404() {
        when(staffProfileRepository.findByTenantIdAndUserId(tenantId, staffUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().generate(tenantId,
                new GeneratePayrollRequest(staffUserId, 3, 2026, BigDecimal.ZERO)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(payrollRecordRepository, never()).saveAndFlush(any());
    }

    @Test
    void generateADuplicateForTheSameStaffMonthAndYearReturns409() {
        when(staffProfileRepository.findByTenantIdAndUserId(tenantId, staffUserId))
                .thenReturn(Optional.of(profile(new BigDecimal("50000.00"))));
        when(payrollRecordRepository.existsByTenantIdAndStaffUserIdAndMonthAndYear(tenantId, staffUserId, 3, 2026))
                .thenReturn(true);

        assertThatThrownBy(() -> service().generate(tenantId,
                new GeneratePayrollRequest(staffUserId, 3, 2026, BigDecimal.ZERO)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(payrollRecordRepository, never()).saveAndFlush(any());
    }

    @Test
    void generateTranslatesAConcurrentInsertRaceIntoAClean409() {
        when(staffProfileRepository.findByTenantIdAndUserId(tenantId, staffUserId))
                .thenReturn(Optional.of(profile(new BigDecimal("50000.00"))));
        when(payrollRecordRepository.existsByTenantIdAndStaffUserIdAndMonthAndYear(tenantId, staffUserId, 3, 2026))
                .thenReturn(false);
        when(payrollRecordRepository.saveAndFlush(any(PayrollRecord.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service().generate(tenantId,
                new GeneratePayrollRequest(staffUserId, 3, 2026, BigDecimal.ZERO)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void ownPayrollDelegatesToTheRepository() {
        when(payrollRecordRepository.findByTenantIdAndStaffUserIdOrderByYearDescMonthDesc(tenantId, staffUserId))
                .thenReturn(List.of());

        service().ownPayroll(tenantId, staffUserId);

        verify(payrollRecordRepository).findByTenantIdAndStaffUserIdOrderByYearDescMonthDesc(tenantId, staffUserId);
    }
}
