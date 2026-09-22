package com.smsapp.student;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AcademicHistoryRepository extends JpaRepository<AcademicHistoryRecord, UUID> {

    List<AcademicHistoryRecord> findByStudentIdOrderByRecordedAtDesc(UUID studentId);

    /** Promotion History screen: every placement change school-wide, newest first. */
    Page<AcademicHistoryRecord> findAllByOrderByRecordedAtDesc(Pageable pageable);

    /** Promotion History screen, filtered to actual promotions (excludes admission/manual reassignment rows). */
    Page<AcademicHistoryRecord> findByChangeReasonOrderByRecordedAtDesc(String changeReason, Pageable pageable);

    /**
     * Duplicate-promotion guard: has this student already been PROMOTED into this academic year?
     * Deliberately scoped to {@code changeReason = 'PROMOTION'} -- an admission or manual section
     * reassignment recorded against the same year must never block a later real promotion into it.
     */
    boolean existsByStudentIdAndAcademicYearIdAndChangeReason(UUID studentId, UUID academicYearId, String changeReason);
}
