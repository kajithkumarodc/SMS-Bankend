package com.smsapp.face;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.face.FaceServiceClient.EmbedResult;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reference-face enrolment: turning a photo of one student into stored embeddings.
 *
 * <p>Two invariants hold everywhere in this class. Nothing is enrolled without live
 * guardian consent, and withdrawing consent deletes the vectors rather than merely
 * flagging them -- an erasure request has to actually erase. Both are enforced here
 * rather than at the controller, so a future caller cannot skip them by accident.
 */
@Service
public class FaceEnrolmentService {

    /**
     * Below this the crop is too small or too uncertain to be a useful reference, and a
     * bad enrolment poisons every later match for that student. Rejecting at upload is
     * far cheaper than debugging a student who never matches.
     */
    private static final double MIN_ENROLMENT_QUALITY = 0.45;

    private final StudentFaceEnrolmentRepository enrolmentRepository;
    private final StudentRepository studentRepository;
    private final FaceConsentService consentService;
    private final FaceServiceClient faceService;
    private final AuditService auditService;
    private final SchoolClock clock;
    private final int maxPerStudent;

    public FaceEnrolmentService(StudentFaceEnrolmentRepository enrolmentRepository,
                               StudentRepository studentRepository,
                               FaceConsentService consentService,
                               FaceServiceClient faceService,
                               AuditService auditService,
                               SchoolClock clock,
                               @Value("${app.face.max-enrolments-per-student:5}") int maxPerStudent) {
        this.enrolmentRepository = enrolmentRepository;
        this.studentRepository = studentRepository;
        this.consentService = consentService;
        this.faceService = faceService;
        this.auditService = auditService;
        this.clock = clock;
        this.maxPerStudent = maxPerStudent;
    }

    /**
     * Enrols one reference photo.
     *
     * @throws ApiException 404 if the student does not exist; 403 if consent is missing
     *         or withdrawn; 400 if the file is empty or not an image; 409 once the
     *         per-student cap is reached; 422 if the photo has no usable single face;
     *         502/503 if the sidecar is unreachable or unconfigured.
     */
    @Transactional
    public StudentFaceEnrolment enrol(UUID studentId, MultipartFile file, UUID enrolledByUserId) {
        Student student = requireStudent(studentId);

        // Consent first, before the image is even read: an un-consented student's photo
        // should not reach the recognition service at all.
        if (!consentService.hasActiveConsent(studentId)) {
            throw new ApiException(
                    "Guardian consent for face attendance is not on record for this student",
                    HttpStatus.FORBIDDEN);
        }

        if (file == null || file.isEmpty()) {
            throw new ApiException("No image was uploaded", HttpStatus.BAD_REQUEST);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ApiException("Enrolment photo must be a JPEG, PNG or WebP image",
                    HttpStatus.BAD_REQUEST);
        }
        if (enrolmentRepository.countByStudentId(studentId) >= maxPerStudent) {
            throw new ApiException(
                    "This student already has the maximum of %d enrolment photos. Remove one first."
                            .formatted(maxPerStudent),
                    HttpStatus.CONFLICT);
        }

        byte[] image;
        try {
            image = file.getBytes();
        } catch (IOException unreadable) {
            throw new ApiException("The uploaded image could not be read", HttpStatus.BAD_REQUEST);
        }

        EmbedResult result = faceService.embed(image, file.getOriginalFilename(), contentType);

        if (result.face().qualityScore() < MIN_ENROLMENT_QUALITY) {
            throw new ApiException(
                    ("That photo is too small or unclear to enrol (quality %.2f, need %.2f). "
                            + "Use a closer, well-lit photo of the student facing the camera.")
                            .formatted(result.face().qualityScore(), MIN_ENROLMENT_QUALITY),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        StudentFaceEnrolment enrolment = new StudentFaceEnrolment();
        enrolment.setStudentId(studentId);
        enrolment.setEmbedding(Embeddings.toBytes(result.face().embedding()));
        enrolment.setDimensions(result.face().dimensions());
        enrolment.setQualityScore((float) result.face().qualityScore());
        enrolment.setModelVersion(result.modelVersion());
        enrolment.setEnrolledAt(clock.now());
        enrolment.setEnrolledByUserId(enrolledByUserId);
        StudentFaceEnrolment saved = enrolmentRepository.save(enrolment);

        // The photo itself is deliberately not retained: the embedding is what the
        // feature needs, and keeping the image too would fail data minimisation.
        auditService.log(AuditActions.FACE_ENROLMENT_ADDED, AuditActions.FACE_ENROLMENT, saved.getId(),
                Map.of("studentId", studentId.toString(),
                        "modelVersion", saved.getModelVersion(),
                        "qualityScore", String.valueOf(saved.getQualityScore()),
                        "studentName", String.valueOf(student.getFullName())));
        return saved;
    }

    /**
     * @throws ApiException 404 if the student does not exist.
     */
    @Transactional(readOnly = true)
    public List<StudentFaceEnrolment> list(UUID studentId) {
        requireStudent(studentId);
        return enrolmentRepository.findByStudentIdOrderByEnrolledAtDesc(studentId);
    }

    /**
     * Removes one enrolment photo.
     *
     * @throws ApiException 404 if the student or the enrolment does not exist, or the
     *         enrolment belongs to a different student.
     */
    @Transactional
    public void remove(UUID studentId, UUID enrolmentId) {
        requireStudent(studentId);
        StudentFaceEnrolment enrolment = enrolmentRepository.findById(enrolmentId)
                .filter(candidate -> candidate.getStudentId().equals(studentId))
                .orElseThrow(() -> new ApiException("Enrolment not found", HttpStatus.NOT_FOUND));

        enrolmentRepository.delete(enrolment);
        auditService.log(AuditActions.FACE_ENROLMENT_REMOVED, AuditActions.FACE_ENROLMENT, enrolmentId,
                Map.of("studentId", studentId.toString()));
    }

    /** The withdrawn consent row plus how many embeddings were destroyed with it. */
    public record ErasureResult(FaceConsent consent, int embeddingsDeleted) {
    }

    /**
     * Withdraws consent and deletes every stored vector for that student, in one
     * transaction.
     *
     * <p>This is the only method callers should use to honour a withdrawal: recording
     * the decision without erasing the biometrics would leave the system holding data
     * it has no basis to hold. Safe to retry -- both halves are idempotent.
     *
     * @return the withdrawn consent row and how many embeddings were destroyed. The row
     *         is returned rather than just the count so the caller can report the real
     *         revocation timestamp instead of asserting nulls it has not checked.
     * @throws ApiException 404 if the student does not exist, or has no consent record.
     */
    @Transactional
    public ErasureResult revokeConsentAndErase(UUID studentId) {
        FaceConsent consent = consentService.revoke(studentId);

        long held = enrolmentRepository.countByStudentId(studentId);
        if (held > 0) {
            enrolmentRepository.deleteByStudentId(studentId);
            auditService.log(AuditActions.FACE_ENROLMENT_ERASED, AuditActions.FACE_ENROLMENT, studentId,
                    Map.of("studentId", studentId.toString(), "embeddingsDeleted", String.valueOf(held)));
        }
        return new ErasureResult(consent, (int) held);
    }

    /**
     * Every reference vector for the consented members of a roster, on one model.
     *
     * <p>The consent filter is applied here rather than left to the caller: this is the
     * method the matching path will use, and a section is normally only partly enrolled,
     * so silently returning an un-consented student's vector would be the exact failure
     * this design exists to prevent.
     */
    @Transactional(readOnly = true)
    public List<StudentFaceEnrolment> referencesFor(Set<UUID> rosterStudentIds, String modelVersion) {
        Set<UUID> consented = consentService.consentedAmong(rosterStudentIds);
        if (consented.isEmpty()) {
            return List.of();
        }
        return enrolmentRepository.findByStudentIdInAndModelVersion(consented, modelVersion);
    }

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private Student requireStudent(UUID studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
    }
}
