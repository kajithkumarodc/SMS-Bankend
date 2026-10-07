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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.generator.EventType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One Front Office complaint (V36). */
@Entity
@Table(name = "complaints")
@Getter
@Setter
@NoArgsConstructor
public class Complaint extends UuidEntity {

    /** "Complain #": assigned by the database identity column on insert, and never written by the app. */
    @Generated(event = EventType.INSERT)
    @Column(name = "complaint_no", insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Long complaintNo;

    @Column(name = "complaint_type_id")
    private UUID complaintTypeId;

    /** Read-only view of {@link #complaintTypeId}, mapped only so the list can sort by type name. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "complaint_type_id", insertable = false, updatable = false)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private ComplaintType complaintType;

    /** The shared Front Office source list (enquiry_sources). */
    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "complain_by", nullable = false, length = 200)
    private String complainBy;

    @Column(length = 30)
    private String phone;

    @Column(name = "complaint_date", nullable = false)
    private LocalDate complaintDate;

    @Column
    private String description;

    @Column(name = "action_taken", length = 500)
    private String actionTaken;

    @Column(length = 200)
    private String assigned;

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

    @Column(name = "created_by_user_id", updatable = false)
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
