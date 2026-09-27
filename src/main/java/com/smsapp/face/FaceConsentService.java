package com.smsapp.face;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Guardian consent for face enrolment.
 *
 * <p>Consent is collected even though educational institutions have limited relief from
 * the DPDP verifiable-consent requirement: the relief is conditioned on processing being
 * strictly necessary and proportionate, and a recorded per-family opt-in is what makes
 * that defensible. Every read path that touches biometric data asks this service first,
 * so an un-consented or withdrawn student is simply never processed.
 */
@Service
public class FaceConsentService {

    private final FaceConsentRepository consentRepository;
    private final StudentRepository studentRepository;
    private final AuditService auditService;
    private final SchoolClock clock;

    public FaceConsentService(FaceConsentRepository consentRepository,
                              StudentRepository studentRepository,
                              AuditService auditService, SchoolClock clock) {
        this.consentRepository = consentRepository;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Grants (or re-grants) consent for one student.
     *
     * @param guardianUserId the granting guardian, or null when an admin is recording a
     *                       paper consent form.
     * @throws ApiException 404 if the student does not exist.
     */
    @Transactional
    public FaceConsent grant(UUID studentId, UUID guardianUserId) {
        requireStudent(studentId);

        FaceConsent consent = consentRepository
                .findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE)
                .orElseGet(() -> {
                    FaceConsent fresh = new FaceConsent();
                    fresh.setStudentId(studentId);
                    fresh.setScope(FaceConsent.SCOPE_FACE_ATTENDANCE);
                    return fresh;
                });

        consent.setGuardianUserId(guardianUserId);
        consent.setGrantedAt(clock.now());
        // Re-granting clears any earlier withdrawal rather than leaving the row
        // simultaneously granted and revoked.
        consent.setRevokedAt(null);
        FaceConsent saved = consentRepository.save(consent);

        auditService.log(AuditActions.FACE_CONSENT_GRANTED, AuditActions.FACE_CONSENT, saved.getId(),
                Map.of("studentId", studentId.toString(), "scope", saved.getScope()));
        return saved;
    }

    /**
     * Withdraws consent. The caller is responsible for deleting the stored embeddings --
     * see {@code FaceEnrolmentService}; this only records the decision.
     *
     * @throws ApiException 404 if the student does not exist, or has no consent record.
     */
    @Transactional
    public FaceConsent revoke(UUID studentId) {
        requireStudent(studentId);

        FaceConsent consent = consentRepository
                .findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE)
                .orElseThrow(() -> new ApiException("No face consent on record for this student",
                        HttpStatus.NOT_FOUND));

        // Withdrawing an already-withdrawn consent is not an error: the caller's intent
        // is already satisfied, and failing would make an erasure request retry-unsafe.
        if (consent.isActive()) {
            consent.setRevokedAt(clock.now());
            consent = consentRepository.save(consent);
            auditService.log(AuditActions.FACE_CONSENT_REVOKED, AuditActions.FACE_CONSENT,
                    consent.getId(), Map.of("studentId", studentId.toString()));
        }
        return consent;
    }

    /**
     * The consent row for one student, whatever its state -- absent means never granted.
     *
     * @throws ApiException 404 if the student does not exist.
     */
    @Transactional(readOnly = true)
    public Optional<FaceConsent> find(UUID studentId) {
        requireStudent(studentId);
        return consentRepository.findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE);
    }

    /**
     * @throws ApiException 404 if the student does not exist.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveConsent(UUID studentId) {
        requireStudent(studentId);
        return consentRepository
                .findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE)
                .filter(FaceConsent::isActive)
                .isPresent();
    }

    /**
     * The consented subset of a roster. This is the gate every biometric path goes
     * through: a section is expected to be only partly enrolled, so callers filter to
     * this set rather than assuming whole-class coverage.
     */
    @Transactional(readOnly = true)
    public Set<UUID> consentedAmong(Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) {
            return Set.of();
        }
        return consentRepository
                .findByStudentIdInAndScopeAndRevokedAtIsNull(
                        studentIds, FaceConsent.SCOPE_FACE_ATTENDANCE)
                .stream()
                .map(FaceConsent::getStudentId)
                .collect(Collectors.toSet());
    }

    /** Current consent state for a set of students, for the admin enrolment screen. */
    @Transactional(readOnly = true)
    public List<FaceConsent> forStudents(Collection<UUID> studentIds) {
        return studentIds.isEmpty()
                ? List.of()
                : consentRepository.findByStudentIdInAndScopeAndRevokedAtIsNull(
                        studentIds, FaceConsent.SCOPE_FACE_ATTENDANCE);
    }

    private Student requireStudent(UUID studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
    }
}
