package com.smsapp.face;

import com.smsapp.academics.SectionRepository;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.face.FaceServiceClient.DetectResult;
import com.smsapp.face.FaceServiceClient.DetectedFace;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceCaptureServiceTest {

    private static final SchoolClock CLOCK = new SchoolClock("Asia/Kolkata");
    private static final String MODEL = "insightface/buffalo_l/arcface-512/v1";

    @Mock
    private AttendanceCaptureRepository captureRepository;

    @Mock
    private AttendanceCaptureFaceRepository faceRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private FaceConsentService consentService;

    @Mock
    private FaceServiceClient faceService;

    @Mock
    private AuditService auditService;

    @TempDir
    Path storage;

    private final UUID sectionId = UUID.randomUUID();
    private final UUID captureId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID teacherId = UUID.randomUUID();

    private AttendanceCaptureService service() {
        return new AttendanceCaptureService(captureRepository, faceRepository, sectionRepository,
                studentRepository, consentService, faceService, auditService, CLOCK,
                storage.toString(), 7);
    }

    private MultipartFile photo() {
        return new MockMultipartFile("file", "room.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private DetectedFace face(double quality, List<Integer> bbox) {
        float[] embedding = new float[512];
        embedding[0] = 1f;
        return new DetectedFace(embedding, 512, quality, bbox);
    }

    private void sectionExists() {
        lenient().when(sectionRepository.existsById(sectionId)).thenReturn(true);
        lenient().when(captureRepository.save(any())).thenAnswer(call -> {
            AttendanceCapture c = call.getArgument(0);
            if (c.getId() == null) c.setId(captureId);
            return c;
        });
        lenient().when(faceRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private AttendanceCapture capture() {
        AttendanceCapture capture = new AttendanceCapture();
        capture.setId(captureId);
        capture.setSectionId(sectionId);
        capture.setCaptureDate(CLOCK.today());
        return capture;
    }

    private Student student(UUID inSection) {
        Student student = new Student();
        student.setId(studentId);
        student.setFullName("Aarav Sharma");
        student.setSectionId(inSection);
        return student;
    }

    @Test
    void storesEveryDetectedFaceUntaggedWithAPurgeWindow() {
        sectionExists();
        when(faceService.detect(any(), any(), anyString())).thenReturn(new DetectResult(
                MODEL, 2, List.of(face(0.9, List.of(10, 20, 200, 200)),
                                  face(0.6, List.of(300, 40, 90, 90)))));

        AttendanceCapture saved = service().capture(sectionId, null, photo(), teacherId);

        assertThat(saved.getFacesDetected()).isEqualTo(2);
        assertThat(saved.getModelVersion()).isEqualTo(MODEL);
        assertThat(saved.getCaptureDate()).isEqualTo(CLOCK.today());
        // The retention window is what makes the consent screen's "deleted within 7
        // days" true, so it must be set at capture time, not left for later.
        assertThat(saved.getPhotoPurgeAfter()).isAfter(CLOCK.now().plusDays(6));
        verify(faceRepository, org.mockito.Mockito.times(2)).save(any());
    }

    /** Nothing in phase 2 proposes an identity: every face starts with no student. */
    @Test
    void detectedFacesStartUntagged() {
        sectionExists();
        when(faceService.detect(any(), any(), anyString()))
                .thenReturn(new DetectResult(MODEL, 1, List.of(face(0.9, List.of(1, 2, 3, 4)))));

        service().capture(sectionId, null, photo(), teacherId);

        org.mockito.ArgumentCaptor<AttendanceCaptureFace> captor =
                org.mockito.ArgumentCaptor.forClass(AttendanceCaptureFace.class);
        verify(faceRepository).save(captor.capture());
        assertThat(captor.getValue().getAssignedStudentId()).isNull();
        assertThat(captor.getValue().isTagged()).isFalse();
        assertThat(captor.getValue().getBbox()).isEqualTo("1,2,3,4");
    }

    @Test
    void anEmptyRoomIsRecordedRatherThanRejected() {
        sectionExists();
        when(faceService.detect(any(), any(), anyString()))
                .thenReturn(new DetectResult(MODEL, 0, List.of()));

        AttendanceCapture saved = service().capture(sectionId, null, photo(), teacherId);

        assertThat(saved.getFacesDetected()).isZero();
        verify(faceRepository, never()).save(any());
    }

    @Test
    void reportsAnUnknownSectionAs404() {
        when(sectionRepository.existsById(sectionId)).thenReturn(false);

        assertThatThrownBy(() -> service().capture(sectionId, null, photo(), teacherId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(faceService, never()).detect(any(), any(), anyString());
    }

    @Test
    void rejectsAFutureCaptureDate() {
        when(sectionRepository.existsById(sectionId)).thenReturn(true);

        assertThatThrownBy(() -> service()
                .capture(sectionId, CLOCK.today().plusDays(1), photo(), teacherId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(faceService, never()).detect(any(), any(), anyString());
    }

    @Test
    void rejectsANonImageUpload() {
        when(sectionRepository.existsById(sectionId)).thenReturn(true);
        MultipartFile pdf = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[] {1});

        assertThatThrownBy(() -> service().capture(sectionId, null, pdf, teacherId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(faceService, never()).detect(any(), any(), anyString());
    }

    // --- tagging ---------------------------------------------------------

    private AttendanceCaptureFace untaggedFace() {
        AttendanceCaptureFace face = new AttendanceCaptureFace();
        face.setId(UUID.randomUUID());
        face.setCaptureId(captureId);
        face.setFaceIndex(0);
        face.setBbox("1,2,3,4");
        face.setEmbedding(new byte[4]);
        face.setDimensions(512);
        return face;
    }

    @Test
    void taggingAFaceRecordsTheStudentAndWhoDidIt() {
        when(captureRepository.findById(captureId)).thenReturn(Optional.of(capture()));
        when(faceRepository.findByCaptureIdAndFaceIndex(captureId, 0))
                .thenReturn(Optional.of(untaggedFace()));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student(sectionId)));
        when(consentService.hasActiveConsent(studentId)).thenReturn(true);
        when(faceRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        AttendanceCaptureFace tagged = service().tagFace(captureId, 0, studentId, teacherId);

        assertThat(tagged.getAssignedStudentId()).isEqualTo(studentId);
        assertThat(tagged.getAssignedByUserId()).isEqualTo(teacherId);
        assertThat(tagged.getAssignedAt()).isNotNull();
        assertThat(tagged.isTagged()).isTrue();
    }

    /**
     * The consent gate for phase 2. Tagging is the first moment the system knows whose
     * face it holds, so it is the only place consent can be enforced -- and it must be,
     * or an un-consented child's vector would be permanently bound to their identity.
     */
    @Test
    void refusesToTagAStudentWithoutConsent() {
        when(captureRepository.findById(captureId)).thenReturn(Optional.of(capture()));
        when(faceRepository.findByCaptureIdAndFaceIndex(captureId, 0))
                .thenReturn(Optional.of(untaggedFace()));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student(sectionId)));
        when(consentService.hasActiveConsent(studentId)).thenReturn(false);

        assertThatThrownBy(() -> service().tagFace(captureId, 0, studentId, teacherId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);

        verify(faceRepository, never()).save(any());
    }

    @Test
    void refusesAStudentFromAnotherSection() {
        when(captureRepository.findById(captureId)).thenReturn(Optional.of(capture()));
        when(faceRepository.findByCaptureIdAndFaceIndex(captureId, 0))
                .thenReturn(Optional.of(untaggedFace()));
        when(studentRepository.findById(studentId))
                .thenReturn(Optional.of(student(UUID.randomUUID())));

        assertThatThrownBy(() -> service().tagFace(captureId, 0, studentId, teacherId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(faceRepository, never()).save(any());
    }

    /** Undoing a mis-tag must not need consent -- it only ever removes an association. */
    @Test
    void clearingATagNeedsNoConsentCheck() {
        AttendanceCaptureFace tagged = untaggedFace();
        tagged.setAssignedStudentId(studentId);
        tagged.setAssignedAt(CLOCK.now());
        when(captureRepository.findById(captureId)).thenReturn(Optional.of(capture()));
        when(faceRepository.findByCaptureIdAndFaceIndex(captureId, 0)).thenReturn(Optional.of(tagged));
        when(faceRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        AttendanceCaptureFace cleared = service().tagFace(captureId, 0, null, teacherId);

        assertThat(cleared.getAssignedStudentId()).isNull();
        assertThat(cleared.getAssignedAt()).isNull();
        verify(consentService, never()).hasActiveConsent(any());
    }

    @Test
    void reportsAnUnknownFaceIndexAs404() {
        when(captureRepository.findById(captureId)).thenReturn(Optional.of(capture()));
        when(faceRepository.findByCaptureIdAndFaceIndex(captureId, 99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().tagFace(captureId, 99, studentId, teacherId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reportsAnUnknownCaptureAs404() {
        when(captureRepository.findById(captureId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().faces(captureId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
