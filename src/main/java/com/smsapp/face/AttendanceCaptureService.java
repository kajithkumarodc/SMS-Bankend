package com.smsapp.face;

import com.smsapp.academics.SectionRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.face.FaceServiceClient.DetectResult;
import com.smsapp.face.FaceServiceClient.DetectedFace;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2: a teacher photographs the class, the detector finds the faces, and the
 * teacher tags each one by hand. No recognition and no matching -- nothing here
 * proposes who anyone is.
 *
 * <p>The hand-tagging is the whole point. Enrolment photos taken at a desk do not
 * match faces under classroom lighting, so this is what builds the labelled corpus
 * phase 3 needs to work on the back row.
 *
 * <p>Consent is applied at tagging rather than at capture, because that is where it
 * can actually be applied: detection sees whoever is in the frame, and the system
 * cannot know who they are until a human says. An untagged face therefore carries a
 * vector for a child with no established consent basis, which is why those rows die
 * with the capture's retention window.
 */
@Service
public class AttendanceCaptureService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceCaptureService.class);

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final AttendanceCaptureRepository captureRepository;
    private final AttendanceCaptureFaceRepository faceRepository;
    private final SectionRepository sectionRepository;
    private final StudentRepository studentRepository;
    private final FaceConsentService consentService;
    private final FaceServiceClient faceService;
    private final AuditService auditService;
    private final SchoolClock clock;
    private final Path baseDir;
    private final int retentionDays;

    public AttendanceCaptureService(AttendanceCaptureRepository captureRepository,
                                    AttendanceCaptureFaceRepository faceRepository,
                                    SectionRepository sectionRepository,
                                    StudentRepository studentRepository,
                                    FaceConsentService consentService,
                                    FaceServiceClient faceService,
                                    AuditService auditService,
                                    SchoolClock clock,
                                    @Value("${app.storage.base-dir}") String baseDir,
                                    @Value("${app.face.photo-retention-days:7}") int retentionDays) {
        this.captureRepository = captureRepository;
        this.faceRepository = faceRepository;
        this.sectionRepository = sectionRepository;
        this.studentRepository = studentRepository;
        this.consentService = consentService;
        this.faceService = faceService;
        this.auditService = auditService;
        this.clock = clock;
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
        this.retentionDays = retentionDays;
    }

    /**
     * Detects every face in a classroom photo and records them untagged.
     *
     * @throws ApiException 404 if the section does not exist; 400 if the file is empty,
     *         not an image, or the date is in the future; 502/503 if the recognition
     *         service is unreachable or unconfigured.
     */
    @Transactional
    public AttendanceCapture capture(UUID sectionId, LocalDate date, MultipartFile file,
                                     UUID capturedByUserId) {
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        LocalDate captureDate = date != null ? date : clock.today();
        if (captureDate.isAfter(clock.today())) {
            throw new ApiException("Capture date cannot be in the future", HttpStatus.BAD_REQUEST);
        }
        if (file == null || file.isEmpty()) {
            throw new ApiException("No image was uploaded", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ApiException("Capture must be a JPEG, PNG or WebP image", HttpStatus.BAD_REQUEST);
        }

        byte[] image;
        try {
            image = file.getBytes();
        } catch (IOException unreadable) {
            throw new ApiException("The uploaded image could not be read", HttpStatus.BAD_REQUEST);
        }

        DetectResult detected = faceService.detect(image, file.getOriginalFilename(), contentType);

        AttendanceCapture capture = new AttendanceCapture();
        capture.setSectionId(sectionId);
        capture.setCaptureDate(captureDate);
        capture.setCapturedByUserId(capturedByUserId);
        capture.setModelVersion(detected.modelVersion());
        capture.setFacesDetected(detected.facesDetected());
        capture.setPhotoPurgeAfter(clock.now().plusDays(retentionDays));
        AttendanceCapture saved = captureRepository.save(capture);

        // Stored only after the row exists, so the path can be keyed by capture id and a
        // failure here leaves a capture with no photo rather than a file nothing owns.
        saved.setPhotoPath(storePhoto(saved.getId(), image));
        saved = captureRepository.save(saved);

        int index = 0;
        for (DetectedFace face : detected.faces()) {
            AttendanceCaptureFace row = new AttendanceCaptureFace();
            row.setCaptureId(saved.getId());
            row.setFaceIndex(index++);
            row.setBbox(face.bboxAsString() == null ? "0,0,0,0" : face.bboxAsString());
            row.setEmbedding(Embeddings.toBytes(face.embedding()));
            row.setDimensions(face.dimensions());
            row.setQualityScore((float) face.qualityScore());
            faceRepository.save(row);
        }

        auditService.log(AuditActions.ATTENDANCE_CAPTURE_CREATED, AuditActions.ATTENDANCE_CAPTURE,
                saved.getId(), Map.of("sectionId", sectionId.toString(),
                        "captureDate", captureDate.toString(),
                        "facesDetected", String.valueOf(detected.facesDetected())));
        return saved;
    }

    /**
     * @throws ApiException 404 if no such capture.
     */
    @Transactional(readOnly = true)
    public AttendanceCapture get(UUID captureId) {
        return requireCapture(captureId);
    }

    /**
     * @throws ApiException 404 if no such capture.
     */
    @Transactional(readOnly = true)
    public List<AttendanceCaptureFace> faces(UUID captureId) {
        requireCapture(captureId);
        return faceRepository.findByCaptureIdOrderByFaceIndexAsc(captureId);
    }

    /** A section's captures for one day, so a teacher can return to an earlier photo. */
    @Transactional(readOnly = true)
    public List<AttendanceCapture> forSectionOnDate(UUID sectionId, LocalDate date) {
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        return captureRepository.findBySectionIdAndCaptureDateOrderByCreatedAtDesc(sectionId, date);
    }

    /**
     * Tags one detected face as a given student, or clears the tag when {@code studentId}
     * is null.
     *
     * <p>Consent is checked here and nowhere else, because this is the first moment the
     * system knows whose face it is holding.
     *
     * @throws ApiException 404 if the capture, face or student does not exist; 403 if the
     *         student has no live consent; 400 if the student is not on that section's roster.
     */
    @Transactional
    public AttendanceCaptureFace tagFace(UUID captureId, int faceIndex, UUID studentId,
                                         UUID taggedByUserId) {
        AttendanceCapture capture = requireCapture(captureId);
        AttendanceCaptureFace face = faceRepository
                .findByCaptureIdAndFaceIndex(captureId, faceIndex)
                .orElseThrow(() -> new ApiException("Face not found in this capture",
                        HttpStatus.NOT_FOUND));

        if (studentId == null) {
            face.setAssignedStudentId(null);
            face.setAssignedByUserId(null);
            face.setAssignedAt(null);
            return faceRepository.save(face);
        }

        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
        if (!capture.getSectionId().equals(student.getSectionId())) {
            throw new ApiException("Student is not in the section this photo was taken for",
                    HttpStatus.BAD_REQUEST);
        }
        if (!consentService.hasActiveConsent(studentId)) {
            throw new ApiException(
                    "Guardian consent for face attendance is not on record for this student",
                    HttpStatus.FORBIDDEN);
        }

        face.setAssignedStudentId(studentId);
        face.setAssignedByUserId(taggedByUserId);
        face.setAssignedAt(clock.now());
        AttendanceCaptureFace saved = faceRepository.save(face);

        auditService.log(AuditActions.CAPTURE_FACE_TAGGED, AuditActions.ATTENDANCE_CAPTURE_FACE,
                saved.getId(), Map.of("captureId", captureId.toString(),
                        "studentId", studentId.toString(),
                        "faceIndex", String.valueOf(faceIndex)));
        return saved;
    }

    /** Erasure: withdrawing consent must take a student's tagged capture faces with it. */
    @Transactional
    public void eraseTaggedFacesFor(UUID studentId) {
        faceRepository.deleteByAssignedStudentId(studentId);
    }

    private AttendanceCapture requireCapture(UUID captureId) {
        return captureRepository.findById(captureId)
                .orElseThrow(() -> new ApiException("Capture not found", HttpStatus.NOT_FOUND));
    }

    /**
     * Writes the photo under the storage root, keyed by capture id.
     *
     * @return the stored path, or null if it could not be written -- a capture without a
     *         retained photo is still perfectly usable for tagging, since the app holds
     *         the image it just took, so this must not fail the whole request.
     */
    private String storePhoto(UUID captureId, byte[] image) {
        Path dir = baseDir.resolve("captures").normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(captureId + ".jpg");
            Files.copy(new java.io.ByteArrayInputStream(image), target,
                    StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException unwritable) {
            log.warn("could not store capture photo for {}", captureId, unwritable);
            return null;
        }
    }
}
