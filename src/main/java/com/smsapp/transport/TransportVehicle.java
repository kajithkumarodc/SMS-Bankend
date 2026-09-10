package com.smsapp.transport;

import com.smsapp.common.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One vehicle. {@code routeId} is null when the vehicle is unassigned.
 * {@code (tenant_id, registration_number)} is unique (V15).
 */
@Entity
@Table(name = "transport_vehicles")
@Getter
@Setter
@NoArgsConstructor
public class TransportVehicle extends TenantScopedEntity {

    /** The route this vehicle runs, or null if unassigned. */
    @Column(name = "route_id")
    private UUID routeId;

    @Column(name = "registration_number", nullable = false, length = 30)
    private String registrationNumber;

    @Column(name = "driver_name", nullable = false, length = 200)
    private String driverName;

    @Column(name = "driver_contact", length = 50)
    private String driverContact;

    @Column(nullable = false)
    private int capacity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
