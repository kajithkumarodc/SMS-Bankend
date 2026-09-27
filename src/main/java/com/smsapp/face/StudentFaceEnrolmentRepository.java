package com.smsapp.face;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StudentFaceEnrolmentRepository extends JpaRepository<StudentFaceEnrolment, UUID> {

    List<StudentFaceEnrolment> findByStudentIdOrderByEnrolledAtDesc(UUID studentId);

    /**
     * Every reference vector for a set of students on one model. Scoped by
     * model_version so a stale enrolment from a previous model is never compared
     * against a fresh capture.
     */
    List<StudentFaceEnrolment> findByStudentIdInAndModelVersion(
            Collection<UUID> studentIds, String modelVersion);

    long countByStudentId(UUID studentId);

    /** Erasure: what withdrawing consent has to delete. */
    void deleteByStudentId(UUID studentId);
}
