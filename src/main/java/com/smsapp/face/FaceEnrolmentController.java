package com.smsapp.face;

import com.smsapp.user.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reference-face enrolment, SCHOOL_ADMIN only.
 *
 * <p>Not open to TEACHER: creating a biometric record of a child is an administrative
 * act, distinct from marking a register. Every write here requires live guardian
 * consent, enforced in {@link FaceEnrolmentService} rather than at this layer.
 *
 * <p>Embeddings are never returned. There is no endpoint that hands a vector back out
 * -- it would be re-identifiable biometric data leaving the system for no operational
 * reason.
 */
@RestController
@RequestMapping("/api/v1/students/{studentId}/face-enrolments")
@PreAuthorize(Roles.HAS_ADMIN)
public class FaceEnrolmentController {

    private final FaceEnrolmentService enrolmentService;

    public FaceEnrolmentController(FaceEnrolmentService enrolmentService) {
        this.enrolmentService = enrolmentService;
    }

    /** Metadata about a stored reference face. Deliberately carries no embedding. */
    public record EnrolmentResponse(
            UUID id,
            UUID studentId,
            int dimensions,
            Float qualityScore,
            String modelVersion,
            OffsetDateTime enrolledAt,
            UUID enrolledByUserId) {

        static EnrolmentResponse from(StudentFaceEnrolment enrolment) {
            return new EnrolmentResponse(enrolment.getId(), enrolment.getStudentId(),
                    enrolment.getDimensions(), enrolment.getQualityScore(),
                    enrolment.getModelVersion(), enrolment.getEnrolledAt(),
                    enrolment.getEnrolledByUserId());
        }
    }

    public record ErasureResponse(UUID studentId, int embeddingsDeleted) {
    }

    /**
     * Adds one reference photo. The image is processed and discarded -- only the
     * embedding is stored.
     *
     * <p>403 if guardian consent is not on record; 409 once the per-student cap is hit;
     * 422 if the photo has no usable single face; 502 or 503 if the recognition service
     * is unreachable or not configured on this deployment.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<EnrolmentResponse> enrol(@PathVariable UUID studentId,
                                                   @RequestPart("file") MultipartFile file,
                                                   Authentication authentication) {
        StudentFaceEnrolment saved = enrolmentService.enrol(studentId, file, userId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(EnrolmentResponse.from(saved));
    }

    /** The student's stored reference faces, newest first. 404 if the student does not exist. */
    @GetMapping
    List<EnrolmentResponse> list(@PathVariable UUID studentId) {
        return enrolmentService.list(studentId).stream().map(EnrolmentResponse::from).toList();
    }

    /** Removes one reference photo. 404 if it does not belong to this student. */
    @DeleteMapping("/{enrolmentId}")
    public ResponseEntity<Void> remove(@PathVariable UUID studentId, @PathVariable UUID enrolmentId) {
        enrolmentService.remove(studentId, enrolmentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Honours a withdrawal: revokes consent and destroys every stored vector for the
     * student, reporting how many were deleted. Safe to retry. 404 if the student has no
     * consent record to withdraw.
     */
    @DeleteMapping
    ErasureResponse erase(@PathVariable UUID studentId) {
        return new ErasureResponse(studentId, enrolmentService.revokeConsentAndErase(studentId));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
