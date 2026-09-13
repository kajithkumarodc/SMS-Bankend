package com.smsapp.payroll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface PayrollRecordRepository extends JpaRepository<PayrollRecord, UUID> {

    boolean existsByTenantIdAndStaffUserIdAndMonthAndYear(UUID tenantId, UUID staffUserId, int month, int year);

    /** A staff member's own payroll history, newest first -- used by {@code /me/payroll}. */
    List<PayrollRecord> findByTenantIdAndStaffUserIdOrderByYearDescMonthDesc(UUID tenantId, UUID staffUserId);
}
