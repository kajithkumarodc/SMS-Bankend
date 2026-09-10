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

/** One hostel block, e.g. "Block A". Rooms belong to a block. */
@Entity
@Table(name = "hostel_blocks")
@Getter
@Setter
@NoArgsConstructor
public class HostelBlock extends TenantScopedEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
