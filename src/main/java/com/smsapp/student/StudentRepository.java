package com.smsapp.student;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link JpaSpecificationExecutor} powers the student search/filter (plan Phase 3 section 4 --
 * name/admission number/roll number/enrollment number/parent/phone/national id/local id, plus
 * academic-session/class/section/category/gender/status/RTE filters). Same reasoning as
 * {@code AuditLogRepository}/{@code AdmissionEnquiryRepository}: composing many optional filters as
 * JPQL {@code :x is null} predicates trips up Postgres's parameter type inference, so a
 * {@link org.springframework.data.jpa.domain.Specification} is used instead.
 */
public interface StudentRepository extends JpaRepository<Student, UUID>, JpaSpecificationExecutor<Student> {

    Page<Student> findBySectionId(UUID sectionId, Pageable pageable);

    /** Transport: all students assigned to one route. */
    List<Student> findByTransportRouteIdOrderByFullName(UUID transportRouteId);

    /** Hostel: all students allocated to one room. */
    List<Student> findByHostelRoomIdOrderByFullName(UUID hostelRoomId);

    /** Hostel: how many students are currently allocated to a room (for the capacity check). */
    long countByHostelRoomId(UUID hostelRoomId);

    boolean existsByAdmissionNumber(String admissionNumber);

    /** Dashboard gender breakdown (e.g. "MALE", "FEMALE"). Students with no gender set are excluded. */
    long countByGender(String gender);

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

    /** Siblings: every other student sharing the same family group. */
    List<Student> findByFamilyIdAndIdNotOrderByFullName(UUID familyId, UUID excludedStudentId);

    /** Teacher Panel (Phase 4): students in any of a teacher's assigned sections. */
    Page<Student> findBySectionIdInOrderByFullName(java.util.Collection<UUID> sectionIds, Pageable pageable);

    /** Teacher Panel dashboard: how many students fall under a teacher's assigned sections. */
    long countBySectionIdIn(java.util.Collection<UUID> sectionIds);

    /**
     * Teacher ownership check: the given student only if it is in one of the teacher's assigned
     * sections. An empty result means "not this teacher's student" -- reported as 404, never 403,
     * same pattern as {@link #findByIdAndGuardianUserId}.
     */
    Optional<Student> findByIdAndSectionIdIn(UUID id, java.util.Collection<UUID> sectionIds);
}
