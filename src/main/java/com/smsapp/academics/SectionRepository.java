package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface SectionRepository extends JpaRepository<Section, UUID> {

    List<Section> findByTenantIdOrderByName(UUID tenantId);

    boolean existsByTenantIdAndClassIdAndName(UUID tenantId, UUID classId, String name);

    boolean existsByIdAndTenantId(UUID id, UUID tenantId);
}
