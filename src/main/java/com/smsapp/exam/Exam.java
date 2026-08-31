package com.smsapp.exam;

import com.smsapp.common.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A scheduled exam for one class + subject (e.g. "Mid-term 2026"). */
@Entity
@Table(name = "exams")
@Getter
@Setter
@NoArgsConstructor
public class Exam extends TenantScopedEntity {

    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "exam_date", nullable = false)
    private LocalDate examDate;

    @Column(name = "max_marks", nullable = false, precision = 6, scale = 2)
    private BigDecimal maxMarks;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
