package com.smsapp.admission;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.student.StudentDocumentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * File storage for admission-application documents (plan Phase 4.5 part 6). Deliberately the SAME
 * local/self-hosted root ({@code app.storage.base-dir}) and the SAME content-type allow-list as
 * {@link StudentDocumentService} (reused via its static helpers, never redefined) -- just a different
 * subfolder ({@code applications/{id}/...}) since the application isn't a student yet. There is no
 * public/static URL for any file here; the only read paths are the authenticated admin download and,
 * internally, the approval conversion that adopts a file into the new student's own document storage.
 */
@Service
public class AdmissionApplicationDocumentService {

    private final AdmissionApplicationDocumentRepository documentRepository;
    private final AdmissionApplicationRepository applicationRepository;
    private final AuditService auditService;
    private final Path baseDir;

    public AdmissionApplicationDocumentService(AdmissionApplicationDocumentRepository documentRepository,
                                                AdmissionApplicationRepository applicationRepository,
                                                AuditService auditService,
                                                @Value("${app.storage.base-dir}") String baseDir) {
        this.documentRepository = documentRepository;
        this.applicationRepository = applicationRepository;
        this.auditService = auditService;
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    /**
     * @throws ApiException 404 if the application doesn't exist, 400 if the file is empty, too large, or
     *         not an allowed type.
     */
    @Transactional
    public AdmissionApplicationDocument upload(UUID applicationId, String documentType, MultipartFile file,
                                                String notes) {
        if (!applicationRepository.existsById(applicationId)) {
            throw new ApiException("Application not found", HttpStatus.NOT_FOUND);
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException("The file is empty", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > StudentDocumentService.maxFileSizeBytes()) {
            throw new ApiException("The file exceeds the 10 MB limit", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType();
        String extension = StudentDocumentService.allowedExtensionFor(contentType);
        if (extension == null) {
            throw new ApiException(
                    "Unsupported file type -- allowed: PDF, JPG, PNG, WEBP, DOC, DOCX", HttpStatus.BAD_REQUEST);
        }
        if (documentType == null || documentType.isBlank()) {
            throw new ApiException("Document type is required", HttpStatus.BAD_REQUEST);
        }

        String storedFilename = UUID.randomUUID() + "." + extension;
        Path appDir = baseDir.resolve("applications").resolve(applicationId.toString()).normalize();
        if (!appDir.startsWith(baseDir)) {
            throw new ApiException("Invalid storage path", HttpStatus.BAD_REQUEST);
        }

        try {
            Files.createDirectories(appDir);
            Files.copy(file.getInputStream(), appDir.resolve(storedFilename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the uploaded file", e);
        }

        AdmissionApplicationDocument document = new AdmissionApplicationDocument();
        document.setApplicationId(applicationId);
        document.setDocumentType(documentType.trim());
        document.setOriginalFilename(sanitizeDisplayName(file.getOriginalFilename()));
        document.setStoredFilename(storedFilename);
        document.setContentType(contentType);
        document.setFileSizeBytes(file.getSize());
        document.setNotes(notes != null && !notes.isBlank() ? notes.trim() : null);

        AdmissionApplicationDocument saved = documentRepository.save(document);
        auditService.log(AuditActions.ADMISSION_APPLICATION_DOCUMENT_UPLOADED,
                AuditActions.ADMISSION_APPLICATION_DOCUMENT, saved.getId(),
                Map.of("applicationId", applicationId.toString(), "documentType", saved.getDocumentType()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<AdmissionApplicationDocument> list(UUID applicationId) {
        return documentRepository.findByApplicationIdOrderByUploadedAtDesc(applicationId);
    }

    /** @throws ApiException 404 if no such document for this application. */
    @Transactional(readOnly = true)
    public AdmissionApplicationDocument get(UUID applicationId, UUID documentId) {
        return documentRepository.findByIdAndApplicationId(documentId, applicationId)
                .orElseThrow(() -> new ApiException("Document not found", HttpStatus.NOT_FOUND));
    }

    /** The file content for the admin download endpoint. Never exposed publicly. */
    @Transactional(readOnly = true)
    public Resource load(UUID applicationId, UUID documentId) {
        AdmissionApplicationDocument document = get(applicationId, documentId);
        Path file = resolvePath(applicationId, document);
        if (!Files.isReadable(file)) {
            throw new ApiException("Document file not found", HttpStatus.NOT_FOUND);
        }
        return new FileSystemResource(file);
    }

    /** The on-disk path of a document -- used internally by the approval conversion to adopt it into student storage. */
    @Transactional(readOnly = true)
    public Path resolvePath(UUID applicationId, AdmissionApplicationDocument document) {
        return baseDir.resolve("applications").resolve(applicationId.toString()).resolve(document.getStoredFilename());
    }

    /** @throws ApiException 404 if no such document for this application. */
    @Transactional
    public void delete(UUID applicationId, UUID documentId) {
        AdmissionApplicationDocument document = get(applicationId, documentId);
        Path file = resolvePath(applicationId, document);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the stored file", e);
        }
        documentRepository.delete(document);
        auditService.log(AuditActions.ADMISSION_APPLICATION_DOCUMENT_DELETED,
                AuditActions.ADMISSION_APPLICATION_DOCUMENT, documentId,
                Map.of("applicationId", applicationId.toString()));
    }

    private static String sanitizeDisplayName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "document";
        }
        String name = rawName.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        String base = lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
