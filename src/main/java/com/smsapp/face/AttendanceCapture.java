package com.smsapp.face;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One classroom photo submitted for processing.
 *
 * <p>The photo itself is short-lived by design: {@link #photoPurgeAfter} is when it
 * stops being kept, and {@code CapturePhotoPurgeService} deletes it and clears
 * {@link #photoPath}. Everything else on this row -- what was detected, what was
 * proposed, who confirmed it -- survives, because that is the audit trail for an
 * attendance record. Retaining forty children's faces indefinitely would fail the
 * "minimum data necessary" condition the DPDP education exemption rests on.
 */
@Entity
@Table(name = "attendance_captures")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceCapture extends UuidEntity {

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "capture_date", nullable = false)
    private LocalDate captureDate;

    @Column(name = "captured_by_user_id")
    private UUID capturedByUserId;

    @Column(name = "model_version", length = 80)
    private String modelVersion;

    @Column(name = "faces_detected")
    private Integer facesDetected;

    /** Null once the photo has been purged -- or if it was never retained at all. */
    @Column(name = "photo_path", length = 500)
    private String photoPath;

    @Column(name = "photo_purge_after")
    private OffsetDateTime photoPurgeAfter;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
