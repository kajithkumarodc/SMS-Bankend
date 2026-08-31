package com.smsapp.academics;

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

/** A section within a class (e.g. "A"). */
@Entity
@Table(name = "sections")
@Getter
@Setter
@NoArgsConstructor
public class Section extends TenantScopedEntity {

    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;

    @Column(nullable = false, length = 100)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
