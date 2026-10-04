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

/** One uploaded document of a staff member (Resume, Joining Letter, ...), at most one per kind (V44). */
@Entity
@Table(name = "staff_documents")
@Getter
@Setter
@NoArgsConstructor
public class StaffDocument extends UuidEntity {

    @Column(name = "staff_profile_id", nullable = false)
    private UUID staffProfileId;

    /** One of {@link StaffDocumentKind}'s names. */
    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false, length = 100)
    private String storedFilename;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
