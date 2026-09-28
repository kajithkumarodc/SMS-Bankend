package com.smsapp.face;

import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2 of face-assisted attendance: capture and hand-tagging. SCHOOL_ADMIN or
 * TEACHER, because photographing your own class is a teacher's job -- unlike
 * enrolment, which creates the biometric record and stays admin-only.
 *
 * <p>Nothing here proposes an identity or touches an attendance record. The detector
 * says "there are 23 faces and here is where they are"; a human says who they are.
 */
@RestController
@RequestMapping("/api/v1/attendance-captures")
@PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
public class AttendanceCaptureController {

    private final AttendanceCaptureService captureService;

    public AttendanceCaptureController(AttendanceCaptureService captureService) {
        this.captureService = captureService;
    }

    public record CaptureResponse(
            UUID id,
            UUID sectionId,
            LocalDate captureDate,
            String modelVersion,
            Integer facesDetected,
            OffsetDateTime photoPurgeAfter,
            OffsetDateTime createdAt) {

        static CaptureResponse from(AttendanceCapture capture) {
            return new CaptureResponse(capture.getId(), capture.getSectionId(),
                    capture.getCaptureDate(), capture.getModelVersion(),
                    capture.getFacesDetected(), capture.getPhotoPurgeAfter(),
                    capture.getCreatedAt());
        }
    }

    /**
     * One detected face. Carries no embedding -- the app crops the face out of the
     * photo it already holds using {@code bbox}, so there is no reason to send a
     * re-identifiable vector back to a phone.
     */
    public record FaceResponse(
            int faceIndex,
            String bbox,
            Float qualityScore,
            UUID assignedStudentId,
            OffsetDateTime assignedAt) {

        static FaceResponse from(AttendanceCaptureFace face) {
            return new FaceResponse(face.getFaceIndex(), face.getBbox(), face.getQualityScore(),
                    face.getAssignedStudentId(), face.getAssignedAt());
        }
    }

    /** A null {@code studentId} clears the tag, which is how a mis-tag is undone. */
    public record TagFaceRequest(UUID studentId) {
    }

    /**
     * Photographs a class and detects the faces in it. The photo is retained only until
     * its purge window closes; the detected faces start untagged.
     *
     * <p>404 if the section does not exist; 400 for a non-image, an empty upload or a
     * future date; 502 or 503 if the recognition service is unreachable or not configured.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<CaptureResponse> capture(
            @RequestParam UUID sectionId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        AttendanceCapture created = captureService.capture(sectionId, date, file, userId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(CaptureResponse.from(created));
    }

    /** A section's captures for one day, newest first. 404 if the section does not exist. */
    @GetMapping
    List<CaptureResponse> list(
            @RequestParam UUID sectionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return captureService.forSectionOnDate(sectionId, date).stream()
                .map(CaptureResponse::from).toList();
    }

    /** 404 if no such capture. */
    @GetMapping("/{captureId}")
    CaptureResponse get(@PathVariable UUID captureId) {
        return CaptureResponse.from(captureService.get(captureId));
    }

    /** Every face found in the capture, in detector order (largest first). */
    @GetMapping("/{captureId}/faces")
    List<FaceResponse> faces(@PathVariable UUID captureId) {
        return captureService.faces(captureId).stream().map(FaceResponse::from).toList();
    }

    /**
     * Tags one face as a student, or clears the tag with a null {@code studentId}.
     *
     * <p>403 if that student has no guardian consent on record -- this is the moment the
     * system first knows whose face it is holding, so it is where consent is enforced.
     * 400 if the student is not on that section's roster; 404 if the capture, face or
     * student does not exist.
     */
    @PutMapping("/{captureId}/faces/{faceIndex}")
    FaceResponse tagFace(@PathVariable UUID captureId,
                         @PathVariable @Min(0) int faceIndex,
                         @Valid @RequestBody TagFaceRequest request,
                         Authentication authentication) {
        return FaceResponse.from(captureService.tagFace(
                captureId, faceIndex, request.studentId(), userId(authentication)));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
