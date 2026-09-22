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

/**
 * One append-only snapshot of "this student was in this class/section during this academic year"
 * (plan Phase 3 section 7: "Student -> Academic Session -> Class -> Section... do not overwrite
 * historical records during promotion"). {@code students.section_id} is only ever the current
 * placement; this table is the history, written by {@link AcademicHistoryService} whenever that
 * placement changes and never updated or deleted afterward.
 */
@Entity
@Table(name = "student_academic_history")
@Getter
@Setter
@NoArgsConstructor
public class AcademicHistoryRecord extends UuidEntity {

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "academic_year_id")
    private UUID academicYearId;

    @Column(name = "class_id")
    private UUID classId;

    @Column(name = "section_id")
    private UUID sectionId;

    /** Who performed the change that produced this row (resolved from the security context, may be null). */
    @Column(name = "recorded_by_user_id")
    private UUID recordedByUserId;

    /** One of {@link AcademicChangeReason}. */
    @Column(name = "change_reason", nullable = false, length = 20)
    private String changeReason;

    @CreationTimestamp
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;
}
