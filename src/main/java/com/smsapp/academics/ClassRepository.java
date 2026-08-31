package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface ClassRepository extends JpaRepository<SchoolClass, UUID> {

    List<SchoolClass> findByTenantIdOrderByName(UUID tenantId);

    Optional<SchoolClass> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndSchoolIdAndName(UUID tenantId, UUID schoolId, String name);
}
