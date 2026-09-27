package com.smsapp.homework;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.Section;
import com.smsapp.academics.SectionRepository;
import com.smsapp.academics.SubjectRepository;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.homework.HomeworkDtos.CreateHomeworkRequest;
import com.smsapp.homework.HomeworkDtos.RecordSubmissionRequest;
import com.smsapp.homework.HomeworkDtos.StudentHomeworkResponse;
import com.smsapp.homework.HomeworkDtos.SubmissionRowResponse;
import com.smsapp.homework.HomeworkDtos.UpdateHomeworkRequest;
import com.smsapp.homework.HomeworkService.SubmissionResult;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeworkServiceTest {

    /** A real clock rather than a mock: it is a value object, and the tests'
     * date expectations only make sense against the school's own zone. */
    private static final SchoolClock CLOCK = new SchoolClock("Asia/Kolkata");

    @Mock
    private HomeworkRepository homeworkRepository;

    @Mock
    private HomeworkSubmissionRepository submissionRepository;

    @Mock
    private ClassRepository classRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AuditService auditService;

    private final UUID classId = UUID.randomUUID();
    private final UUID sectionId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();
    private final UUID teacherId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID homeworkId = UUID.randomUUID();

    private HomeworkService service() {
        return new HomeworkService(homeworkRepository, submissionRepository, classRepository, sectionRepository,
                subjectRepository, studentRepository, auditService, CLOCK);
    }

    private void academicsExist() {
        lenient().when(classRepository.existsById(classId)).thenReturn(true);
        lenient().when(subjectRepository.existsById(subjectId)).thenReturn(true);
        lenient().when(sectionRepository.findById(sectionId)).thenReturn(Optional.of(section(classId)));
    }

    private Section section(UUID owningClassId) {
        Section section = new Section();
        section.setClassId(owningClassId);
        return section;
    }

    private Homework homework() {
        Homework homework = new Homework();
        homework.setId(homeworkId);
        homework.setClassId(classId);
        homework.setSectionId(sectionId);
        homework.setSubjectId(subjectId);
        homework.setTitle("Fractions worksheet");
        homework.setAssignedDate(CLOCK.today());
        homework.setDueDate(CLOCK.today().plusDays(2));
        return homework;
    }

    private Student student(UUID id, String name, UUID inSection) {
        Student student = new Student();
        student.setId(id);
        student.setFullName(name);
        student.setSectionId(inSection);
        return student;
    }

    private CreateHomeworkRequest createRequest(LocalDate assignedDate, LocalDate dueDate) {
        return new CreateHomeworkRequest(classId, sectionId, subjectId, "  Fractions worksheet  ",
                "  Exercises 1-10  ", assignedDate, dueDate);
    }

    @Test
    void createsHomeworkTrimmingTextAndDefaultingTheAssignedDateToToday() {
        academicsExist();
        when(homeworkRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        Homework saved = service().create(teacherId, createRequest(null, CLOCK.today().plusDays(3)));

        assertThat(saved.getTitle()).isEqualTo("Fractions worksheet");
        assertThat(saved.getDescription()).isEqualTo("Exercises 1-10");
        assertThat(saved.getAssignedDate()).isEqualTo(CLOCK.today());
        assertThat(saved.getCreatedByUserId()).isEqualTo(teacherId);
    }

    @Test
    void rejectsADueDateBeforeTheAssignedDateWith400() {
        academicsExist();

        assertThatThrownBy(() -> service()
                .create(teacherId, createRequest(CLOCK.today(), CLOCK.today().minusDays(1))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(homeworkRepository, never()).save(any());
    }

    @Test
    void rejectsASectionThatBelongsToADifferentClassWith400() {
        when(classRepository.existsById(classId)).thenReturn(true);
        when(subjectRepository.existsById(subjectId)).thenReturn(true);
        when(sectionRepository.findById(sectionId)).thenReturn(Optional.of(section(UUID.randomUUID())));

        assertThatThrownBy(() -> service()
                .create(teacherId, createRequest(CLOCK.today(), CLOCK.today().plusDays(1))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(homeworkRepository, never()).save(any());
    }

    @Test
    void reportsAnUnknownSubjectAs404() {
        when(classRepository.existsById(classId)).thenReturn(true);
        when(subjectRepository.existsById(subjectId)).thenReturn(false);

        assertThatThrownBy(() -> service()
                .create(teacherId, createRequest(CLOCK.today(), CLOCK.today().plusDays(1))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateKeepsTheAssignedDateAndRejectsAnEarlierDueDate() {
        Homework existing = homework();
        when(homeworkRepository.findById(homeworkId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service().update(homeworkId,
                new UpdateHomeworkRequest("Fractions", null, existing.getAssignedDate().minusDays(1))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(homeworkRepository, never()).save(any());
    }

    @Test
    void submissionSheetShowsEveryStudentWithUnmarkedOnesPending() {
        UUID otherStudentId = UUID.randomUUID();
        when(homeworkRepository.findById(homeworkId)).thenReturn(Optional.of(homework()));

        HomeworkSubmission recorded = new HomeworkSubmission();
        recorded.setHomeworkId(homeworkId);
        recorded.setStudentId(studentId);
        recorded.setStatus(HomeworkSubmissionStatus.SUBMITTED);
        when(submissionRepository.findByHomeworkId(homeworkId)).thenReturn(List.of(recorded));

        when(studentRepository.findBySectionIdInOrderByFullName(anyCollection(), any()))
                .thenReturn(new PageImpl<>(List.of(
                        student(studentId, "Aarav Sharma", sectionId),
                        student(otherStudentId, "Ananya Iyer", sectionId))));

        List<SubmissionRowResponse> sheet = service().submissionSheet(homeworkId);

        assertThat(sheet).hasSize(2);
        assertThat(sheet.get(0).status()).isEqualTo(HomeworkSubmissionStatus.SUBMITTED);
        assertThat(sheet.get(1).studentName()).isEqualTo("Ananya Iyer");
        assertThat(sheet.get(1).status()).isEqualTo(HomeworkSubmissionStatus.PENDING);
    }

    @Test
    void recordingASubmissionForTheFirstTimeReportsItAsCreatedAndStampsTheTime() {
        when(homeworkRepository.findById(homeworkId)).thenReturn(Optional.of(homework()));
        when(studentRepository.findById(studentId))
                .thenReturn(Optional.of(student(studentId, "Aarav Sharma", sectionId)));
        when(submissionRepository.findByHomeworkIdAndStudentId(homeworkId, studentId)).thenReturn(Optional.empty());
        when(submissionRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        SubmissionResult result = service().recordSubmission(teacherId, homeworkId, studentId,
                new RecordSubmissionRequest("submitted", "  Neat work  "));

        assertThat(result.created()).isTrue();
        assertThat(result.submission().getStatus()).isEqualTo(HomeworkSubmissionStatus.SUBMITTED);
        assertThat(result.submission().getSubmittedAt()).isNotNull();
        assertThat(result.submission().getRemarks()).isEqualTo("Neat work");
        assertThat(result.submission().getMarkedByUserId()).isEqualTo(teacherId);
    }

    @Test
    void movingAStudentBackToPendingClearsTheSubmissionTime() {
        HomeworkSubmission existing = new HomeworkSubmission();
        existing.setHomeworkId(homeworkId);
        existing.setStudentId(studentId);
        existing.setStatus(HomeworkSubmissionStatus.SUBMITTED);
        existing.setSubmittedAt(CLOCK.now());

        when(homeworkRepository.findById(homeworkId)).thenReturn(Optional.of(homework()));
        when(studentRepository.findById(studentId))
                .thenReturn(Optional.of(student(studentId, "Aarav Sharma", sectionId)));
        when(submissionRepository.findByHomeworkIdAndStudentId(homeworkId, studentId))
                .thenReturn(Optional.of(existing));
        when(submissionRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        SubmissionResult result = service().recordSubmission(teacherId, homeworkId, studentId,
                new RecordSubmissionRequest("PENDING", null));

        assertThat(result.created()).isFalse();
        assertThat(result.submission().getSubmittedAt()).isNull();
    }

    @Test
    void rejectsAStudentFromAnotherSectionWith400() {
        when(homeworkRepository.findById(homeworkId)).thenReturn(Optional.of(homework()));
        when(studentRepository.findById(studentId))
                .thenReturn(Optional.of(student(studentId, "Aarav Sharma", UUID.randomUUID())));

        assertThatThrownBy(() -> service().recordSubmission(teacherId, homeworkId, studentId,
                new RecordSubmissionRequest("SUBMITTED", null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(submissionRepository, never()).save(any());
    }

    @Test
    void rejectsAnUnknownStatusWith400() {
        when(homeworkRepository.findById(homeworkId)).thenReturn(Optional.of(homework()));

        assertThatThrownBy(() -> service().recordSubmission(teacherId, homeworkId, studentId,
                new RecordSubmissionRequest("HANDED_IN_LATER", null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(submissionRepository, never()).save(any());
    }

    @Test
    void studentViewCarriesTheirOwnStateAndDefaultsToPending() {
        UUID otherHomeworkId = UUID.randomUUID();
        Homework first = homework();
        Homework second = homework();
        second.setId(otherHomeworkId);
        second.setTitle("Reading log");

        when(homeworkRepository.findBySectionIdInOrderByAssignedDateDescCreatedAtDesc(anyCollection()))
                .thenReturn(List.of(first, second));

        HomeworkSubmission own = new HomeworkSubmission();
        own.setHomeworkId(homeworkId);
        own.setStudentId(studentId);
        own.setStatus(HomeworkSubmissionStatus.LATE);
        when(submissionRepository.findByStudentIdAndHomeworkIdIn(any(), anyCollection())).thenReturn(List.of(own));

        List<StudentHomeworkResponse> rows = service().forStudent(student(studentId, "Aarav", sectionId), null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).status()).isEqualTo(HomeworkSubmissionStatus.LATE);
        assertThat(rows.get(1).title()).isEqualTo("Reading log");
        assertThat(rows.get(1).status()).isEqualTo(HomeworkSubmissionStatus.PENDING);
    }

    @Test
    void aStudentWithNoSectionGetsAnEmptyListRatherThanAnError() {
        assertThat(service().forStudent(student(studentId, "Aarav", null), null)).isEmpty();
        verify(homeworkRepository, never()).findBySectionIdInOrderByAssignedDateDescCreatedAtDesc(anyCollection());
    }

    @Test
    void reportsUnknownHomeworkAs404() {
        when(homeworkRepository.findById(homeworkId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(homeworkId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listForSectionReportsAnUnknownSectionAs404() {
        when(sectionRepository.findById(sectionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listForSection(sectionId, null, Pageable.unpaged()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
