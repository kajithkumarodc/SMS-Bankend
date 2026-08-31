package com.smsapp.attendance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
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

    /**
     * Attendance for every student currently assigned to {@code sectionId}, on {@code date}.
     * Joins students by id (attendance_records has no section column). May be partial
     * or empty when the section has not been fully marked yet.
     */
    @Query("select a from AttendanceRecord a, Student s "
            + "where a.studentId = s.id and a.tenantId = :tenantId "
            + "and s.sectionId = :sectionId and a.date = :date")
    List<AttendanceRecord> findForSectionOnDate(@Param("tenantId") UUID tenantId,
                                                @Param("sectionId") UUID sectionId,
                                                @Param("date") LocalDate date);
}
