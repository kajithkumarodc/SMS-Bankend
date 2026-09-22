package com.smsapp.student;

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
 * Metadata for one uploaded student document. The file itself lives on local disk under
 * {@link StudentDocumentService}'s storage root, named by {@link #storedFilename} (a random id, never
 * derived from user input) -- never served by a public/static path, only through the authenticated
 * download endpoint.
 */
@Entity
@Table(name = "student_documents")
@Getter
@Setter
@NoArgsConstructor
public class StudentDocument extends UuidEntity {

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

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

    @Column(name = "uploaded_by_user_id")
    private UUID uploadedByUserId;

    @Column(length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private OffsetDateTime uploadedAt;
}
