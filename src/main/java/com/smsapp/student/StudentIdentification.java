package com.smsapp.student;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A configurable identification document (National ID, Local ID, Birth certificate, ...) -- free-text type, not a hardcoded enum. */
@Entity
@Table(name = "student_identifications")
@Getter
@Setter
@NoArgsConstructor
public class StudentIdentification extends UuidEntity {

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "id_type", nullable = false, length = 50)
    private String idType;

    @Column(name = "id_value", nullable = false, length = 200)
    private String idValue;

    @Column(length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
