package com.smsapp.academics;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** Medium of instruction (e.g. "English Medium"). Fees can differ per medium (V40). */
@Entity
@Table(name = "mediums")
@Getter
@Setter
@NoArgsConstructor
public class Medium extends UuidEntity {

    @Column(nullable = false, length = 100)
    private String name;

    /** Inactive mediums stay on existing students and fees but can't be picked for new ones. */
    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
