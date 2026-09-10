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

/** One transport route, e.g. "Route 1 - North Zone". Vehicles and students attach to it. */
@Entity
@Table(name = "transport_routes")
@Getter
@Setter
@NoArgsConstructor
public class TransportRoute extends TenantScopedEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
