package com.smsapp.attendance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Every query is explicitly filtered by {@code tenant_id} -- defense in depth on
 * top of the database RLS policy (plan section 1, "use both together").
 */
public interface AttendanceRepository extends JpaRepository<AttendanceRecord, UUID> {

    Page<AttendanceRecord> findByTenantIdAndDate(UUID tenantId, LocalDate date, Pageable pageable);

    Page<AttendanceRecord> findByTenantIdAndStudentIdOrderByDateDesc(UUID tenantId, UUID studentId, Pageable pageable);

    Optional<AttendanceRecord> findByTenantIdAndStudentIdAndDate(UUID tenantId, UUID studentId, LocalDate date);
}
