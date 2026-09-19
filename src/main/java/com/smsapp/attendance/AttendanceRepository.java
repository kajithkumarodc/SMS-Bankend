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

public interface AttendanceRepository extends JpaRepository<AttendanceRecord, UUID> {

    Page<AttendanceRecord> findByDate(LocalDate date, Pageable pageable);

    Page<AttendanceRecord> findByStudentIdOrderByDateDesc(UUID studentId, Pageable pageable);

    Optional<AttendanceRecord> findByStudentIdAndDate(UUID studentId, LocalDate date);

    /**
     * Attendance for every student currently assigned to {@code sectionId}, on {@code date}.
     * Joins students by id (attendance_records has no section column). May be partial
     * or empty when the section has not been fully marked yet.
     */
    @Query("select a from AttendanceRecord a, Student s "
            + "where a.studentId = s.id and s.sectionId = :sectionId and a.date = :date")
    List<AttendanceRecord> findForSectionOnDate(@Param("sectionId") UUID sectionId, @Param("date") LocalDate date);

    /** Portal dashboard: one student's attendance tallied by status. */
    @Query("select a.status as status, count(a) as total from AttendanceRecord a "
            + "where a.studentId = :studentId group by a.status")
    List<StatusTally> tallyByStatus(@Param("studentId") UUID studentId);

    interface StatusTally {
        String getStatus();

        long getTotal();
    }
}
