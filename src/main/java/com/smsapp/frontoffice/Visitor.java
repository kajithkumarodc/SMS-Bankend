package com.smsapp.frontoffice;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One Visitor Book entry (V32). Whom they met is exactly one of {@link #staffProfileId} / {@link #studentId}. */
@Entity
@Table(name = "visitors")
@Getter
@Setter
@NoArgsConstructor
public class Visitor extends UuidEntity {

    @Column(name = "purpose_id", nullable = false)
    private UUID purposeId;

    /** Read-only view of {@link #purposeId}, mapped only so the list can sort by purpose name. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purpose_id", insertable = false, updatable = false)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private FrontOfficePurpose purpose;

    /** {@link MeetingWithType#STAFF} or {@link MeetingWithType#STUDENT}. */
    @Column(name = "meeting_with_type", nullable = false, length = 10)
    private String meetingWithType;

    @Column(name = "staff_profile_id")
    private UUID staffProfileId;

    @Column(name = "student_id")
    private UUID studentId;

    @Column(name = "visitor_name", nullable = false, length = 200)
    private String visitorName;

    @Column(length = 30)
    private String phone;

    @Column(name = "id_card", length = 100)
    private String idCard;

    @Column(name = "number_of_persons")
    private Short numberOfPersons;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(name = "in_time")
    private LocalTime inTime;

    @Column(name = "out_time")
    private LocalTime outTime;

    @Column
    private String note;

    @Column(name = "attachment_original_filename")
    private String attachmentOriginalFilename;

    @Column(name = "attachment_stored_filename", length = 100)
    private String attachmentStoredFilename;

    @Column(name = "attachment_content_type", length = 150)
    private String attachmentContentType;

    @Column(name = "attachment_size_bytes")
    private Long attachmentSizeBytes;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

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
