package com.smsapp.frontoffice;

import com.smsapp.common.ApiException;
import com.smsapp.student.StudentDocumentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Disk storage for Front Office attachments, with the same rules as student documents
 * ({@link StudentDocumentService}): files live under {@code app.storage.base-dir/<area>/<owner id>/}, outside
 * anything served statically; the on-disk name is a random UUID plus a validated extension (never the
 * uploaded name); the content type must be on the same allow-list; and files are capped at 10 MB.
 */
@Component
public class FrontOfficeFileStore {

    private final Path baseDir;

    public FrontOfficeFileStore(@Value("${app.storage.base-dir}") String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    /** What was written: the display name, the random on-disk name, and type/size for the metadata row. */
    public record StoredFile(String originalFilename, String storedFilename, String contentType, long sizeBytes) {
    }

    /** @throws ApiException 400 if the file is empty, over 10 MB or not an allowed type. */
    public StoredFile store(String area, UUID ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException("The file is empty", HttpStatus.BAD_REQUEST);
        }
        if (file.getSize() > StudentDocumentService.maxFileSizeBytes()) {
            throw new ApiException("The file exceeds the 10 MB limit", HttpStatus.BAD_REQUEST);
        }
        String extension = StudentDocumentService.allowedExtensionFor(file.getContentType());
        if (extension == null) {
            throw new ApiException("Unsupported file type -- allowed: PDF, JPG, PNG, WEBP, DOC, DOCX",
                    HttpStatus.BAD_REQUEST);
        }
        Path dir = ownerDir(area, ownerId);
        String storedFilename = UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(storedFilename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the uploaded file", e);
        }
        return new StoredFile(sanitizeDisplayName(file.getOriginalFilename()), storedFilename, file.getContentType(),
                file.getSize());
    }

    /** @throws ApiException 404 if the file is missing on disk. */
    public Resource load(String area, UUID ownerId, String storedFilename) {
        Path file = ownerDir(area, ownerId).resolve(storedFilename);
        if (!Files.isReadable(file)) {
            throw new ApiException("Document file not found", HttpStatus.NOT_FOUND);
        }
        return new FileSystemResource(file);
    }

    public void delete(String area, UUID ownerId, String storedFilename) {
        try {
            Files.deleteIfExists(ownerDir(area, ownerId).resolve(storedFilename));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the stored file", e);
        }
    }

    /** Removes the owner's folder once it's empty. Best effort -- an empty folder holds no data. */
    public void deleteFolderIfEmpty(String area, UUID ownerId) {
        try {
            Files.deleteIfExists(ownerDir(area, ownerId));
        } catch (IOException e) {
            // Not empty or locked -- leave it.
        }
    }

    /** {@code <base-dir>/<area>/<owner id>}. Both parts are fixed strings or UUIDs, never user input. */
    private Path ownerDir(String area, UUID ownerId) {
        Path dir = baseDir.resolve(area).resolve(ownerId.toString()).normalize();
        if (!dir.startsWith(baseDir)) {
            throw new ApiException("Invalid storage path", HttpStatus.BAD_REQUEST);
        }
        return dir;
    }

    /** Strips any path from a client-supplied filename -- display only, never used as a disk path. */
    private static String sanitizeDisplayName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "document";
        }
        String name = rawName.replace('\\', '/');
        String base = name.substring(name.lastIndexOf('/') + 1);
        return base.length() > 255 ? base.substring(0, 255) : base;
    }
}
