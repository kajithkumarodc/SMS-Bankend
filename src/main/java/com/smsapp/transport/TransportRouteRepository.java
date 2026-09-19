package com.smsapp.transport;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransportRouteRepository extends JpaRepository<TransportRoute, UUID> {

    List<TransportRoute> findAllByOrderByName();
}
