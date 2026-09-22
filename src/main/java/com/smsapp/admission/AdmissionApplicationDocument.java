package com.smsapp.admission;

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
 * Metadata for one document uploaded against an application. The file lives on the same
 * local/self-hosted storage root as {@code StudentDocument} ({@code app.storage.base-dir}), under
 * {@code applications/{applicationId}/...} -- never a public/static path, only through the
 * authenticated admin download endpoint (plan part 6).
 */
@Entity
@Table(name = "admission_application_documents")
@Getter
@Setter
@NoArgsConstructor
public class AdmissionApplicationDocument extends UuidEntity {

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_filename", nullable = false, length = 255)
    private String storedFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;
}
