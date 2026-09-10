package com.smsapp.transport;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface TransportRouteRepository extends JpaRepository<TransportRoute, UUID> {

    List<TransportRoute> findByTenantIdOrderByName(UUID tenantId);

    Optional<TransportRoute> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByIdAndTenantId(UUID id, UUID tenantId);
}
