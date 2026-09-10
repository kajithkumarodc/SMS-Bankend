package com.smsapp.transport;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface TransportVehicleRepository extends JpaRepository<TransportVehicle, UUID> {

    List<TransportVehicle> findByTenantIdOrderByRegistrationNumber(UUID tenantId);

    List<TransportVehicle> findByTenantIdAndRouteIdOrderByRegistrationNumber(UUID tenantId, UUID routeId);

    boolean existsByTenantIdAndRegistrationNumber(UUID tenantId, String registrationNumber);
}
