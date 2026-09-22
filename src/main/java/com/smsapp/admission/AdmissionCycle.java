package com.smsapp.admission;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A named admission window (e.g. "2026-27 Admissions") tied to an existing {@code AcademicYear} --
 * never a parallel session concept. At most one may be {@code OPEN} per school at a time (V27's
 * partial unique index), matching the "one current academic year" precedent.
 */
@Entity
@Table(name = "admission_cycles")
@Getter
@Setter
@NoArgsConstructor
public class AdmissionCycle extends UuidEntity {

    @Column(name = "school_id", nullable = false)
    private UUID schoolId;

    @Column(name = "academic_year_id", nullable = false)
    private UUID academicYearId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;

    @Column(name = "close_date", nullable = false)
    private LocalDate closeDate;

    /** One of {@link AdmissionCycleStatus}. */
    @Column(nullable = false, length = 20)
    private String status = AdmissionCycleStatus.DRAFT;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
