package com.smsapp.face;

import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceConsentServiceTest {

    private static final SchoolClock CLOCK = new SchoolClock("Asia/Kolkata");

    @Mock
    private FaceConsentRepository consentRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AuditService auditService;

    private final UUID studentId = UUID.randomUUID();
    private final UUID guardianUserId = UUID.randomUUID();

    private FaceConsentService service() {
        return new FaceConsentService(consentRepository, studentRepository, auditService, CLOCK);
    }

    private void studentExists() {
        lenient().when(studentRepository.findById(studentId))
                .thenReturn(Optional.of(new Student()));
    }

    private FaceConsent consent(boolean active) {
        FaceConsent consent = new FaceConsent();
        consent.setId(UUID.randomUUID());
        consent.setStudentId(studentId);
        consent.setScope(FaceConsent.SCOPE_FACE_ATTENDANCE);
        consent.setGrantedAt(CLOCK.now());
        if (!active) consent.setRevokedAt(CLOCK.now());
        return consent;
    }

    @Test
    void grantingCreatesAConsentAttributedToTheGuardian() {
        studentExists();
        when(consentRepository.findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE))
                .thenReturn(Optional.empty());
        when(consentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        FaceConsent saved = service().grant(studentId, guardianUserId);

        assertThat(saved.getStudentId()).isEqualTo(studentId);
        assertThat(saved.getGuardianUserId()).isEqualTo(guardianUserId);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getScope()).isEqualTo(FaceConsent.SCOPE_FACE_ATTENDANCE);
    }

    @Test
    void regrantingClearsAnEarlierWithdrawal() {
        studentExists();
        when(consentRepository.findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE))
                .thenReturn(Optional.of(consent(false)));
        when(consentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        FaceConsent saved = service().grant(studentId, guardianUserId);

        assertThat(saved.getRevokedAt()).isNull();
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void revokingStampsTheWithdrawal() {
        studentExists();
        when(consentRepository.findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE))
                .thenReturn(Optional.of(consent(true)));
        when(consentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        FaceConsent saved = service().revoke(studentId);

        assertThat(saved.getRevokedAt()).isNotNull();
        assertThat(saved.isActive()).isFalse();
    }

    /**
     * An erasure request must be safe to retry, so withdrawing twice is a no-op rather
     * than an error -- and must not write a second audit entry claiming it happened again.
     */
    @Test
    void revokingTwiceIsANoOpRatherThanAnError() {
        studentExists();
        when(consentRepository.findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE))
                .thenReturn(Optional.of(consent(false)));

        FaceConsent saved = service().revoke(studentId);

        assertThat(saved.isActive()).isFalse();
        verify(consentRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any());
    }

    @Test
    void revokingWithNothingOnRecordIs404() {
        studentExists();
        when(consentRepository.findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().revoke(studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reportsAnUnknownStudentAs404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().grant(studentId, guardianUserId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(consentRepository, never()).save(any());
    }

    @Test
    void hasActiveConsentIsFalseForAWithdrawnConsent() {
        studentExists();
        when(consentRepository.findByStudentIdAndScope(studentId, FaceConsent.SCOPE_FACE_ATTENDANCE))
                .thenReturn(Optional.of(consent(false)));

        assertThat(service().hasActiveConsent(studentId)).isFalse();
    }

    /**
     * The gate the whole feature depends on: a section is normally only partly enrolled,
     * so the consented subset must be exactly those with live consent and nobody else.
     */
    @Test
    void consentedAmongReturnsOnlyTheConsentedSubset() {
        UUID consented = UUID.randomUUID();
        UUID notConsented = UUID.randomUUID();
        FaceConsent live = new FaceConsent();
        live.setStudentId(consented);

        when(consentRepository.findByStudentIdInAndScopeAndRevokedAtIsNull(
                anyCollection(), eq(FaceConsent.SCOPE_FACE_ATTENDANCE)))
                .thenReturn(List.of(live));

        Set<UUID> result = service().consentedAmong(List.of(consented, notConsented));

        assertThat(result).containsExactly(consented);
    }

    @Test
    void consentedAmongSkipsTheQueryForAnEmptyRoster() {
        assertThat(service().consentedAmong(List.of())).isEmpty();
        verify(consentRepository, never())
                .findByStudentIdInAndScopeAndRevokedAtIsNull(anyCollection(), any());
    }
}
