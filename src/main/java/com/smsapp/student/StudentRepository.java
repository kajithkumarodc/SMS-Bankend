package com.smsapp.student;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudentRepository extends JpaRepository<Student, UUID> {

    Page<Student> findBySectionId(UUID sectionId, Pageable pageable);

    /** Transport: all students assigned to one route. */
    List<Student> findByTransportRouteIdOrderByFullName(UUID transportRouteId);

    /** Hostel: all students allocated to one room. */
    List<Student> findByHostelRoomIdOrderByFullName(UUID hostelRoomId);

    /** Hostel: how many students are currently allocated to a room (for the capacity check). */
    long countByHostelRoomId(UUID hostelRoomId);

    boolean existsByAdmissionNumber(String admissionNumber);

    /** Portal: the student record for a STUDENT-role login. */
    Optional<Student> findByStudentUserId(UUID studentUserId);

    /** Portal: all children of a PARENT-role login (a parent may have several). */
    List<Student> findByGuardianUserIdOrderByFullName(UUID guardianUserId);

    /**
     * Portal ownership check: the given student only if it belongs to this parent.
     * An empty result means "not this parent's child" -- reported as 404, never
     * 403, so it does not leak whether the student exists.
     */
    Optional<Student> findByIdAndGuardianUserId(UUID id, UUID guardianUserId);
}
