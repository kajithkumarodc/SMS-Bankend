package com.smsapp.hostel;

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
 * One room in a block. {@code capacity} caps how many students may be allocated;
 * {@code (tenant_id, block_id, room_number)} is unique (V16).
 */
@Entity
@Table(name = "hostel_rooms")
@Getter
@Setter
@NoArgsConstructor
public class HostelRoom extends TenantScopedEntity {

    @Column(name = "block_id", nullable = false, updatable = false)
    private UUID blockId;

    @Column(name = "room_number", nullable = false, length = 30)
    private String roomNumber;

    @Column(nullable = false)
    private int capacity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
