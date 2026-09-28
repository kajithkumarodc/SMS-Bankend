package com.smsapp.face;

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
 * One face the detector found in a classroom photo.
 *
 * <p>Untagged rows are transient by design: they carry a vector for a child whose
 * guardian may not have consented, so they live only until the capture's retention
 * window closes (see {@code CapturePhotoPurgeService}). A row tagged to a consented
 * student survives, because that is the hand-labelled corpus phase 3 needs.
 */
@Entity
@Table(name = "attendance_capture_faces")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceCaptureFace extends UuidEntity {

    @Column(name = "capture_id", nullable = false, updatable = false)
    private UUID captureId;

    /** Position in the detector's output, largest face first. Stable for the capture's life. */
    @Column(name = "face_index", nullable = false, updatable = false)
    private int faceIndex;

    /** "x,y,w,h" in pixels of the photo as submitted. */
    @Column(nullable = false, length = 60)
    private String bbox;

    @Column(nullable = false)
    private byte[] embedding;

    @Column(nullable = false)
    private int dimensions;

    @Column(name = "quality_score")
    private Float qualityScore;

    @Column(name = "assigned_student_id")
    private UUID assignedStudentId;

    @Column(name = "assigned_by_user_id")
    private UUID assignedByUserId;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public boolean isTagged() {
        return assignedStudentId != null;
    }
}
