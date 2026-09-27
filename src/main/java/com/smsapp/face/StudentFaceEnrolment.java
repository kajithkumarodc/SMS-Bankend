package com.smsapp.face;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One reference embedding for one student. Several rows per student is normal --
 * different angles, and refreshed each term, because children's faces change fast
 * enough that a single enrolment at admission decays within months.
 */
@Entity
@Table(name = "student_face_enrolments")
@Getter
@Setter
@NoArgsConstructor
public class StudentFaceEnrolment extends UuidEntity {

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    /** 512 float32, little-endian. Packed and compared by {@link Embeddings}. */
    @Column(nullable = false)
    private byte[] embedding;

    @Column(nullable = false)
    private int dimensions;

    @Column(name = "quality_score")
    private Float qualityScore;

    @Column(name = "source_photo_ref", length = 500)
    private String sourcePhotoRef;

    /** Vectors from different models are not comparable; this is how that is caught. */
    @Column(name = "model_version", nullable = false, length = 80)
    private String modelVersion;

    @Column(name = "enrolled_at", nullable = false)
    private OffsetDateTime enrolledAt;

    @Column(name = "enrolled_by_user_id")
    private UUID enrolledByUserId;
}
