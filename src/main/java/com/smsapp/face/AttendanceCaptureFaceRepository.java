package com.smsapp.face;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttendanceCaptureFaceRepository extends JpaRepository<AttendanceCaptureFace, UUID> {

    List<AttendanceCaptureFace> findByCaptureIdOrderByFaceIndexAsc(UUID captureId);

    Optional<AttendanceCaptureFace> findByCaptureIdAndFaceIndex(UUID captureId, int faceIndex);

    /**
     * Untagged faces for a capture. These are the ones the purge sweep destroys:
     * untagged means no consented student was ever associated with the vector, so
     * there is no basis to keep it past the retention window.
     */
    List<AttendanceCaptureFace> findByCaptureIdAndAssignedStudentIdIsNull(UUID captureId);

    /** The hand-tagged corpus for one student, which is what phase 3/4 train against. */
    List<AttendanceCaptureFace> findByAssignedStudentIdOrderByCreatedAtDesc(UUID studentId);

    /** Erasure: withdrawing consent must take the tagged capture faces too. */
    void deleteByAssignedStudentId(UUID studentId);
}
