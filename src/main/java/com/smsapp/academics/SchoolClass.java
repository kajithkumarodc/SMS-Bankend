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

/** A class / grade (e.g. "Grade 5"). Named {@code SchoolClass} to avoid {@link java.lang.Class}. */
@Entity
@Table(name = "classes")
@Getter
@Setter
@NoArgsConstructor
public class SchoolClass extends TenantScopedEntity {

    @Column(name = "school_id", nullable = false, updatable = false)
    private UUID schoolId;

    @Column(nullable = false, length = 100)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
