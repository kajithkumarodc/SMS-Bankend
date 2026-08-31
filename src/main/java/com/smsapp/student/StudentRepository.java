package com.smsapp.student;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Every query is explicitly filtered by {@code tenant_id} -- defense in depth on
 * top of the database RLS policy (plan section 1, "use both together").
 */
public interface StudentRepository extends JpaRepository<Student, UUID> {

    Page<Student> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Student> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndAdmissionNumber(UUID tenantId, String admissionNumber);
}
