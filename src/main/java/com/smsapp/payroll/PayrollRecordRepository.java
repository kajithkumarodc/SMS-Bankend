package com.smsapp.payroll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayrollRecordRepository extends JpaRepository<PayrollRecord, UUID> {

    boolean existsByStaffUserIdAndMonthAndYear(UUID staffUserId, int month, int year);

    /** A staff member's own payroll history, newest first -- used by {@code /me/payroll}. */
    List<PayrollRecord> findByStaffUserIdOrderByYearDescMonthDesc(UUID staffUserId);
}
