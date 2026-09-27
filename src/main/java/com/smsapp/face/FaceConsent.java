package com.smsapp.face;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A guardian's consent to enrol one student's face. One row per student per scope;
 * re-granting revives the same row, and the change history lives in audit_log.
 */
@Entity
@Table(name = "face_consents")
@Getter
@Setter
@NoArgsConstructor
public class FaceConsent extends UuidEntity {

    /** The only scope that exists today. A second purpose would be a second value. */
    public static final String SCOPE_FACE_ATTENDANCE = "FACE_ATTENDANCE";

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "guardian_user_id")
    private UUID guardianUserId;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(nullable = false, length = 40)
    private String scope = SCOPE_FACE_ATTENDANCE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /** Consent is live only while it has been granted and not since withdrawn. */
    public boolean isActive() {
        return revokedAt == null;
    }
}
