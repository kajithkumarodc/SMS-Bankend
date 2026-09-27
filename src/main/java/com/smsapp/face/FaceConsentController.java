package com.smsapp.face;

import com.smsapp.common.ApiException;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.user.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Face-enrolment consent.
 *
 * <p>Two audiences on purpose. A PARENT grants or withdraws consent for their own child
 * through {@code /me/children/{studentId}/face-consent} -- ownership-scoped exactly like
 * the rest of the portal, so a mismatched id is a 404 and never a 403. A SCHOOL_ADMIN
 * reads and records consent through {@code /students/{id}/face-consent}, which is how a
 * paper consent form gets into the system.
 *
 * <p>There is deliberately no TEACHER route: enrolling a child's biometrics is an
 * administrative act, not part of marking a register.
 */
@RestController
@RequestMapping("/api/v1")
public class FaceConsentController {

    private final FaceConsentService consentService;
    private final FaceEnrolmentService enrolmentService;
    private final StudentRepository studentRepository;

    public FaceConsentController(FaceConsentService consentService,
                                 FaceEnrolmentService enrolmentService,
                                 StudentRepository studentRepository) {
        this.consentService = consentService;
        this.enrolmentService = enrolmentService;
        this.studentRepository = studentRepository;
    }

    public record FaceConsentResponse(
            UUID studentId,
            boolean consented,
            OffsetDateTime grantedAt,
            OffsetDateTime revokedAt,
            String scope,
            /** Only populated on a withdrawal, so the caller can confirm the erasure happened. */
            Integer embeddingsDeleted) {

        static FaceConsentResponse from(FaceConsent consent) {
            return new FaceConsentResponse(consent.getStudentId(), consent.isActive(),
                    consent.getGrantedAt(), consent.getRevokedAt(), consent.getScope(), null);
        }

        /** Carries the real timestamps off the withdrawn row, not hardcoded nulls. */
        static FaceConsentResponse withdrawn(FaceEnrolmentService.ErasureResult result) {
            FaceConsent consent = result.consent();
            return new FaceConsentResponse(consent.getStudentId(), consent.isActive(),
                    consent.getGrantedAt(), consent.getRevokedAt(), consent.getScope(),
                    result.embeddingsDeleted());
        }

        /** No row at all is a real answer -- never consented -- not a 404. */
        static FaceConsentResponse none(UUID studentId) {
            return new FaceConsentResponse(studentId, false, null, null,
                    FaceConsent.SCOPE_FACE_ATTENDANCE, null);
        }
    }

    // --- PARENT, own child only -------------------------------------------

    /** PARENT: whether they have consented for this child. 404 if not their child. */
    @GetMapping("/me/children/{studentId}/face-consent")
    @PreAuthorize(Roles.HAS_PARENT)
    FaceConsentResponse childConsent(@PathVariable UUID studentId, Authentication authentication) {
        requireOwnChild(studentId, userId(authentication));
        return consentService.find(studentId)
                .map(FaceConsentResponse::from)
                .orElseGet(() -> FaceConsentResponse.none(studentId));
    }

    /** PARENT: consent to face attendance for their own child. 404 if not their child. */
    @PostMapping("/me/children/{studentId}/face-consent")
    @PreAuthorize(Roles.HAS_PARENT)
    FaceConsentResponse grantForChild(@PathVariable UUID studentId, Authentication authentication) {
        UUID guardianUserId = userId(authentication);
        requireOwnChild(studentId, guardianUserId);
        return FaceConsentResponse.from(consentService.grant(studentId, guardianUserId));
    }

    /**
     * PARENT: withdraw consent for their own child. This is a true erasure -- it revokes
     * the consent AND destroys every stored embedding, reporting how many were deleted,
     * because recording the decision without deleting the biometrics would leave the
     * system holding data it no longer has any basis to hold. Withdrawing twice is not an
     * error: the intent is already satisfied, which keeps the request safe to retry.
     * 404 if not their child, or if nothing is on record to withdraw.
     */
    @DeleteMapping("/me/children/{studentId}/face-consent")
    @PreAuthorize(Roles.HAS_PARENT)
    FaceConsentResponse revokeForChild(@PathVariable UUID studentId, Authentication authentication) {
        requireOwnChild(studentId, userId(authentication));
        return FaceConsentResponse.withdrawn(enrolmentService.revokeConsentAndErase(studentId));
    }

    // --- SCHOOL_ADMIN, any student ----------------------------------------

    /** SCHOOL_ADMIN: consent state for any student. 404 if the student does not exist. */
    @GetMapping("/students/{id}/face-consent")
    @PreAuthorize(Roles.HAS_ADMIN)
    FaceConsentResponse studentConsent(@PathVariable UUID id) {
        return consentService.find(id)
                .map(FaceConsentResponse::from)
                .orElseGet(() -> FaceConsentResponse.none(id));
    }

    /**
     * SCHOOL_ADMIN: record a consent obtained outside the app (a signed paper form).
     * No guardian user is attributed, since the grant did not come through their account.
     */
    @PostMapping("/students/{id}/face-consent")
    @PreAuthorize(Roles.HAS_ADMIN)
    FaceConsentResponse recordConsent(@PathVariable UUID id) {
        return FaceConsentResponse.from(consentService.grant(id, null));
    }

    /** SCHOOL_ADMIN: withdraw consent for any student, erasing their embeddings with it. */
    @DeleteMapping("/students/{id}/face-consent")
    @PreAuthorize(Roles.HAS_ADMIN)
    FaceConsentResponse revokeConsent(@PathVariable UUID id) {
        return FaceConsentResponse.withdrawn(enrolmentService.revokeConsentAndErase(id));
    }

    /**
     * Mirrors PortalService's ownership convention: a student who is not this parent's
     * child is reported as missing, so the endpoint never leaks that they exist.
     */
    private Student requireOwnChild(UUID studentId, UUID guardianUserId) {
        return studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)
                .orElseThrow(() -> new ApiException("Child not found", HttpStatus.NOT_FOUND));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
