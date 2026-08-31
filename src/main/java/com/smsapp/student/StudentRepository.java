package com.smsapp.student;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every query is explicitly filtered by {@code tenant_id} -- defense in depth on
 * top of the database RLS policy (plan section 1, "use both together").
 */
public interface StudentRepository extends JpaRepository<Student, UUID> {

    Page<Student> findByTenantId(UUID tenantId, Pageable pageable);

    Page<Student> findByTenantIdAndSectionId(UUID tenantId, UUID sectionId, Pageable pageable);

    Optional<Student> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndAdmissionNumber(UUID tenantId, String admissionNumber);

    /** Portal: the student record for a STUDENT-role login. */
    Optional<Student> findByTenantIdAndStudentUserId(UUID tenantId, UUID studentUserId);

    /** Portal: all children of a PARENT-role login (a parent may have several). */
    List<Student> findByTenantIdAndGuardianUserIdOrderByFullName(UUID tenantId, UUID guardianUserId);

    /**
     * Portal ownership check: the given student only if it belongs to this tenant AND
     * this parent. An empty result means "not this parent's child" -- reported as 404,
     * never 403, so it does not leak whether the student exists.
     */
    Optional<Student> findByIdAndTenantIdAndGuardianUserId(UUID id, UUID tenantId, UUID guardianUserId);
}
