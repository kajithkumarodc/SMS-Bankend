package com.smsapp.staff;

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

/** A student's 1 to 5 star rating of a teacher, Pending until approved (V50). */
@Entity
@Table(name = "teacher_ratings")
@Getter
@Setter
@NoArgsConstructor
public class TeacherRating extends UuidEntity {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";

    @Column(name = "staff_profile_id", nullable = false, updatable = false)
    private UUID staffProfileId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false)
    private int rating;

    @Column(length = 1000)
    private String comment;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "approved_by_user_id")
    private UUID approvedByUserId;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
