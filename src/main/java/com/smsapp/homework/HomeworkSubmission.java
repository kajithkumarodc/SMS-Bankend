package com.smsapp.homework;

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
 * One student's state against one piece of homework. A student with no row is
 * {@link HomeworkSubmissionStatus#PENDING} -- rows exist only once a teacher has
 * recorded something, so setting homework for a section costs one insert, not one
 * per student.
 */
@Entity
@Table(name = "homework_submissions")
@Getter
@Setter
@NoArgsConstructor
public class HomeworkSubmission extends UuidEntity {

    @Column(name = "homework_id", nullable = false, updatable = false)
    private UUID homeworkId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(length = 1000)
    private String remarks;

    @Column(name = "marked_by_user_id")
    private UUID markedByUserId;

    @Column(name = "marked_at")
    private OffsetDateTime markedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
