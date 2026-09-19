package com.smsapp.transport;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransportVehicleRepository extends JpaRepository<TransportVehicle, UUID> {

    List<TransportVehicle> findAllByOrderByRegistrationNumber();

    List<TransportVehicle> findByRouteIdOrderByRegistrationNumber(UUID routeId);

    boolean existsByRegistrationNumber(String registrationNumber);
}
