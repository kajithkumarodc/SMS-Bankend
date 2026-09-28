package com.smsapp.face;

import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.face.FaceServiceClient.DetectedFace;
import com.smsapp.face.FaceServiceClient.EmbedResult;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaceEnrolmentServiceTest {

    private static final SchoolClock CLOCK = new SchoolClock("Asia/Kolkata");
    private static final String MODEL = "insightface/buffalo_l/arcface-512/v1";

    @Mock
    private StudentFaceEnrolmentRepository enrolmentRepository;

    @Mock
    private AttendanceCaptureFaceRepository captureFaceRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private FaceConsentService consentService;

    @Mock
    private FaceServiceClient faceService;

    @Mock
    private AuditService auditService;

    private final UUID studentId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    private FaceEnrolmentService service() {
        return new FaceEnrolmentService(enrolmentRepository, captureFaceRepository,
                studentRepository, consentService, faceService, auditService, CLOCK, 5);
    }

    private void studentExists() {
        Student student = new Student();
        student.setId(studentId);
        student.setFullName("Aarav Sharma");
        lenient().when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
    }

    private MultipartFile photo() {
        return new MockMultipartFile("file", "aarav.jpg", "image/jpeg", new byte[] {1, 2, 3, 4});
    }

    private EmbedResult embedResult(double quality) {
        float[] embedding = new float[512];
        embedding[0] = 1f;
        return new EmbedResult(MODEL, new DetectedFace(embedding, 512, quality, List.of(10, 20, 200, 200)));
    }

    @Test
    void enrolsAPhotoAndStoresTheEmbeddingWithItsModelVersion() {
        studentExists();
        when(consentService.hasActiveConsent(studentId)).thenReturn(true);
        when(enrolmentRepository.countByStudentId(studentId)).thenReturn(0L);
        when(faceService.embed(any(), any(), anyString())).thenReturn(embedResult(0.92));
        when(enrolmentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        StudentFaceEnrolment saved = service().enrol(studentId, photo(), adminId);

        assertThat(saved.getStudentId()).isEqualTo(studentId);
        assertThat(saved.getDimensions()).isEqualTo(512);
        assertThat(saved.getModelVersion()).isEqualTo(MODEL);
        assertThat(saved.getEnrolledByUserId()).isEqualTo(adminId);
        // Stored form must round-trip back to the vector the sidecar sent.
        assertThat(Embeddings.fromBytes(saved.getEmbedding())).hasSize(512);
        // The image itself is never kept -- only the vector.
        assertThat(saved.getSourcePhotoRef()).isNull();
    }

    /**
     * The gate the whole design rests on: no consent means the photo must not even reach
     * the recognition service.
     */
    @Test
    void refusesToEnrolWithoutConsentAndNeverCallsTheFaceService() {
        studentExists();
        when(consentService.hasActiveConsent(studentId)).thenReturn(false);

        assertThatThrownBy(() -> service().enrol(studentId, photo(), adminId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);

        verify(faceService, never()).embed(any(), any(), anyString());
        verify(enrolmentRepository, never()).save(any());
    }

    /** A low-quality reference poisons every later match, so it is refused at upload. */
    @Test
    void rejectsALowQualityFaceWith422() {
        studentExists();
        when(consentService.hasActiveConsent(studentId)).thenReturn(true);
        when(enrolmentRepository.countByStudentId(studentId)).thenReturn(0L);
        when(faceService.embed(any(), any(), anyString())).thenReturn(embedResult(0.2));

        assertThatThrownBy(() -> service().enrol(studentId, photo(), adminId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        verify(enrolmentRepository, never()).save(any());
    }

    @Test
    void rejectsANonImageUploadWith400() {
        studentExists();
        when(consentService.hasActiveConsent(studentId)).thenReturn(true);
        MultipartFile pdf = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1});

        assertThatThrownBy(() -> service().enrol(studentId, pdf, adminId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(faceService, never()).embed(any(), any(), anyString());
    }

    @Test
    void refusesOnceThePerStudentCapIsReached() {
        studentExists();
        when(consentService.hasActiveConsent(studentId)).thenReturn(true);
        when(enrolmentRepository.countByStudentId(studentId)).thenReturn(5L);

        assertThatThrownBy(() -> service().enrol(studentId, photo(), adminId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(faceService, never()).embed(any(), any(), anyString());
    }

    /** Withdrawal must actually erase, not merely record the decision. */
    @Test
    void revokingConsentDeletesEveryStoredEmbedding() {
        FaceConsent revoked = new FaceConsent();
        revoked.setStudentId(studentId);
        revoked.setRevokedAt(CLOCK.now());
        when(consentService.revoke(studentId)).thenReturn(revoked);
        when(enrolmentRepository.countByStudentId(studentId)).thenReturn(3L);

        var result = service().revokeConsentAndErase(studentId);
        int deleted = result.embeddingsDeleted();

        // The withdrawn row travels back so the API can report a real timestamp.
        assertThat(result.consent().getRevokedAt()).isNotNull();
        assertThat(deleted).isEqualTo(3);
        verify(consentService).revoke(studentId);
        verify(enrolmentRepository).deleteByStudentId(studentId);
        // Tagged capture faces are this student's biometrics too -- erasing only the
        // enrolments would leave the corpus holding their face under their name.
        verify(captureFaceRepository).deleteByAssignedStudentId(studentId);
    }

    /** Safe to retry: a second withdrawal deletes nothing and claims nothing. */
    @Test
    void revokingWhenNothingIsStoredDeletesNothing() {
        when(consentService.revoke(studentId)).thenReturn(new FaceConsent());
        when(enrolmentRepository.countByStudentId(studentId)).thenReturn(0L);

        int deleted = service().revokeConsentAndErase(studentId).embeddingsDeleted();

        assertThat(deleted).isZero();
        verify(consentService).revoke(studentId);
        verify(enrolmentRepository, never()).deleteByStudentId(any());
        verify(auditService, never()).log(any(), any(), any(), any());
    }

    /**
     * The matching path must never see an un-consented student's vector, even if one is
     * still stored -- so the consent filter lives inside this method, not in its callers.
     */
    @Test
    void referencesAreFilteredToConsentedStudentsOnly() {
        UUID consented = UUID.randomUUID();
        UUID notConsented = UUID.randomUUID();
        when(consentService.consentedAmong(any())).thenReturn(Set.of(consented));
        when(enrolmentRepository.findByStudentIdInAndModelVersion(anyCollection(), any()))
                .thenReturn(List.of(new StudentFaceEnrolment()));

        service().referencesFor(Set.of(consented, notConsented), MODEL);

        verify(enrolmentRepository).findByStudentIdInAndModelVersion(Set.of(consented), MODEL);
    }

    @Test
    void noConsentedStudentsMeansNoQueryAtAll() {
        when(consentService.consentedAmong(any())).thenReturn(Set.of());

        assertThat(service().referencesFor(Set.of(UUID.randomUUID()), MODEL)).isEmpty();
        verify(enrolmentRepository, never()).findByStudentIdInAndModelVersion(anyCollection(), any());
    }

    @Test
    void removingAnEnrolmentBelongingToAnotherStudentIs404() {
        studentExists();
        StudentFaceEnrolment other = new StudentFaceEnrolment();
        other.setStudentId(UUID.randomUUID());
        UUID enrolmentId = UUID.randomUUID();
        when(enrolmentRepository.findById(enrolmentId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service().remove(studentId, enrolmentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(enrolmentRepository, never()).delete(any());
    }

    @Test
    void reportsAnUnknownStudentAs404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().enrol(studentId, photo(), adminId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
