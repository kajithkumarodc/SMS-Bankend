package com.smsapp.frontoffice;

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

/** A supporting document attached to a {@link PostalDispatch}; the file itself is in {@link FrontOfficeFileStore}. */
@Entity
@Table(name = "postal_dispatch_documents")
@Getter
@Setter
@NoArgsConstructor
public class PostalDispatchDocument extends UuidEntity {

    @Column(name = "dispatch_id", nullable = false, updatable = false)
    private UUID dispatchId;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false, length = 100)
    private String storedFilename;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by_user_id")
    private UUID uploadedByUserId;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;
}
