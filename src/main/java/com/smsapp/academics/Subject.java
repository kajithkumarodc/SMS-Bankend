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
import java.util.UUID;

/** A subject taught at a school (e.g. "Mathematics"). */
@Entity
@Table(name = "subjects")
@Getter
@Setter
@NoArgsConstructor
public class Subject extends UuidEntity {

    @Column(name = "school_id", nullable = false, updatable = false)
    private UUID schoolId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String code;

    @Column(name = "subject_type", nullable = false, length = 20)
    private String subjectType = "THEORY";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
