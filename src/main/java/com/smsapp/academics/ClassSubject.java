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

/** Links a subject to a class -- the class teaches this subject. */
@Entity
@Table(name = "class_subjects")
@Getter
@Setter
@NoArgsConstructor
public class ClassSubject extends TenantScopedEntity {

    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
