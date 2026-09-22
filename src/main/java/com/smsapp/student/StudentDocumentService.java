package com.smsapp.student;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
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
import java.util.Set;
import java.util.UUID;

/**
 * Local/self-hosted file storage for student documents (plan Phase 3 section 2). Files live on disk under
 * {@code app.storage.base-dir}, outside any statically-served directory -- the only way to read one back is
 * {@link #load} through the authenticated, permission-checked download endpoint. No cloud storage dependency
 * of any kind (decisions doc: local storage by default, cloud optional and not built here).
 *
 * <p>Safe-upload rules: the on-disk filename is always a fresh random UUID plus a validated extension --
 * never the caller-supplied filename -- which rules out path traversal and collisions. Content type and
 * extension are both checked against a fixed allow-list, and size is capped independently of Spring's
 * multipart limit (defense in depth: a misconfigured multipart limit shouldn't be the only guard).
 */
@Service
public class StudentDocumentService {

    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB

    private static final Map<String, String> ALLOWED_EXTENSIONS_BY_CONTENT_TYPE = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/webp", "webp"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"));

    private final StudentDocumentRepository documentRepository;
    private final StudentRepository studentRepository;
    private final AuditService auditService;
    private final Path baseDir;

    public StudentDocumentService(StudentDocumentRepository documentRepository, StudentRepository studentRepository,
                                  AuditService auditService, @Value("${app.storage.base-dir}") String baseDir) {
        this.documentRepository = documentRepository;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    /**
     * @throws ApiException 404 if the student doesn't exist, 400 if the file is empty, too large, or not an
     *         allowed type.
     */
    @Transactional
    public StudentDocument upload(UUID studentId, String documentType, MultipartFile file, String notes,
                                  UUID uploadedByUserId) {
        if (!studentRepository.existsById(studentId)) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException("The file is empty", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ApiException("The file exceeds the 10 MB limit", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType();
        String extension = ALLOWED_EXTENSIONS_BY_CONTENT_TYPE.get(contentType);
        if (extension == null) {
            throw new ApiException(
                    "Unsupported file type -- allowed: PDF, JPG, PNG, WEBP, DOC, DOCX", HttpStatus.BAD_REQUEST);
        }
        if (documentType == null || documentType.isBlank()) {
            throw new ApiException("Document type is required", HttpStatus.BAD_REQUEST);
        }

        String storedFilename = UUID.randomUUID() + "." + extension;
        Path studentDir = baseDir.resolve("students").resolve(studentId.toString()).normalize();
        if (!studentDir.startsWith(baseDir)) {
            // Defense in depth -- studentId is a UUID from the path variable, never attacker-controlled
            // free text, so this can't actually happen, but a directory write always gets this check.
            throw new ApiException("Invalid storage path", HttpStatus.BAD_REQUEST);
        }

        try {
            Files.createDirectories(studentDir);
            Path target = studentDir.resolve(storedFilename);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the uploaded file", e);
        }

        StudentDocument document = new StudentDocument();
        document.setStudentId(studentId);
        document.setDocumentType(documentType.trim());
        document.setOriginalFilename(sanitizeDisplayName(file.getOriginalFilename()));
        document.setStoredFilename(storedFilename);
        document.setContentType(contentType);
        document.setFileSizeBytes(file.getSize());
        document.setUploadedByUserId(uploadedByUserId);
        document.setNotes(notes != null && !notes.isBlank() ? notes.trim() : null);

        StudentDocument saved = documentRepository.save(document);
        auditService.log(AuditActions.STUDENT_DOCUMENT_UPLOADED, AuditActions.STUDENT_DOCUMENT, saved.getId(),
                Map.of("studentId", studentId.toString(), "documentType", saved.getDocumentType()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<StudentDocument> list(UUID studentId) {
        return documentRepository.findByStudentIdOrderByUploadedAtDesc(studentId);
    }

    /** @throws ApiException 404 if no such document for this student. */
    @Transactional(readOnly = true)
    public StudentDocument get(UUID studentId, UUID documentId) {
        return documentRepository.findByIdAndStudentId(documentId, studentId)
                .orElseThrow(() -> new ApiException("Document not found", HttpStatus.NOT_FOUND));
    }

    /** The file content for a download response. @throws ApiException 404 if the metadata or the file itself is missing. */
    @Transactional(readOnly = true)
    public Resource load(UUID studentId, UUID documentId) {
        StudentDocument document = get(studentId, documentId);
        Path file = baseDir.resolve("students").resolve(studentId.toString()).resolve(document.getStoredFilename());
        if (!Files.isReadable(file)) {
            throw new ApiException("Document file not found", HttpStatus.NOT_FOUND);
        }
        return new FileSystemResource(file);
    }

    /** @throws ApiException 404 if no such document for this student. */
    @Transactional
    public void delete(UUID studentId, UUID documentId) {
        StudentDocument document = get(studentId, documentId);
        Path file = baseDir.resolve("students").resolve(studentId.toString()).resolve(document.getStoredFilename());
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the stored file", e);
        }
        documentRepository.delete(document);
        auditService.log(AuditActions.STUDENT_DOCUMENT_DELETED, AuditActions.STUDENT_DOCUMENT, documentId,
                Map.of("studentId", studentId.toString()));
    }

    /** Strips any path components from a client-supplied filename -- display/metadata only, never used as a disk path. */
    private static String sanitizeDisplayName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "document";
        }
        String name = rawName.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        String base = lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    /** Content types this service accepts, for the upload form to mirror server-side validation. */
    public static Set<String> allowedContentTypes() {
        return ALLOWED_EXTENSIONS_BY_CONTENT_TYPE.keySet();
    }

    /** The same allow-list, reused by {@code AdmissionApplicationDocumentService} so a second one is never defined. */
    public static String allowedExtensionFor(String contentType) {
        return ALLOWED_EXTENSIONS_BY_CONTENT_TYPE.get(contentType);
    }

    public static long maxFileSizeBytes() {
        return MAX_FILE_SIZE_BYTES;
    }

    /**
     * Copies an already-validated file (from admission-application storage, see plan Phase 4.5 part 18)
     * into this student's own document storage and records it as a normal {@link StudentDocument} --
     * the approved application's paperwork becomes part of the student's real record, not a second,
     * parallel place documents live.
     *
     * @throws ApiException 404 if the student doesn't exist.
     */
    @Transactional
    public StudentDocument adoptApplicationDocument(UUID studentId, Path sourceFile, String documentType,
                                                     String originalFilename, String contentType,
                                                     long fileSizeBytes, UUID uploadedByUserId) {
        if (!studentRepository.existsById(studentId)) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        String extension = ALLOWED_EXTENSIONS_BY_CONTENT_TYPE.getOrDefault(contentType, "bin");
        String storedFilename = UUID.randomUUID() + "." + extension;
        Path studentDir = baseDir.resolve("students").resolve(studentId.toString()).normalize();
        if (!studentDir.startsWith(baseDir)) {
            throw new ApiException("Invalid storage path", HttpStatus.BAD_REQUEST);
        }

        try {
            Files.createDirectories(studentDir);
            Files.copy(sourceFile, studentDir.resolve(storedFilename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy the admission application document", e);
        }

        StudentDocument document = new StudentDocument();
        document.setStudentId(studentId);
        document.setDocumentType(documentType);
        document.setOriginalFilename(sanitizeDisplayName(originalFilename));
        document.setStoredFilename(storedFilename);
        document.setContentType(contentType);
        document.setFileSizeBytes(fileSizeBytes);
        document.setUploadedByUserId(uploadedByUserId);
        document.setNotes("Imported from the online admission application");

        StudentDocument saved = documentRepository.save(document);
        auditService.log(AuditActions.STUDENT_DOCUMENT_UPLOADED, AuditActions.STUDENT_DOCUMENT, saved.getId(),
                Map.of("studentId", studentId.toString(), "documentType", saved.getDocumentType(),
                        "source", "admission_application"));
        return saved;
    }
}
