package com.smsapp.staff;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A staff member's leave request. {@code staffUserId} references {@code users}
 * directly (not {@link StaffProfile}) -- the requester's identity is the user
 * account itself.
 */
@Entity
@Table(name = "leave_requests")
@Getter
@Setter
@NoArgsConstructor
public class LeaveRequest extends UuidEntity {

    @Column(name = "staff_user_id", nullable = false)
    private UUID staffUserId;

    @Column(name = "leave_type", nullable = false, length = 50)
    private String leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 1000)
    private String reason;

    /** The leave type list entry (V47); {@link #leaveType} is its name. */
    @Column(name = "leave_type_id")
    private UUID leaveTypeId;

    /** FIRST_HALF or SECOND_HALF for a half-day leave, else null. */
    @Column(name = "half_day", length = 20)
    private String halfDay;

    /** Calendar days from start to end, or 0.5 for a half day. */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal days = BigDecimal.ONE;

    @Column(name = "apply_date", nullable = false)
    private LocalDate applyDate = LocalDate.now();

    @Column(length = 2000)
    private String note;

    @Column(name = "attachment_original_filename")
    private String attachmentOriginalFilename;

    @Column(name = "attachment_stored_filename", length = 100)
    private String attachmentStoredFilename;

    @Column(name = "attachment_content_type", length = 150)
    private String attachmentContentType;

    @Column(name = "attachment_size_bytes")
    private Long attachmentSizeBytes;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean hasAttachment() {
        return attachmentStoredFilename != null;
    }
}
