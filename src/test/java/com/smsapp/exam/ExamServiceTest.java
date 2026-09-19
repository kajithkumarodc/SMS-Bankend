package com.smsapp.exam;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SchoolClass;
import com.smsapp.academics.Subject;
import com.smsapp.academics.SubjectRepository;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.exam.ExamDtos.CreateExamRequest;
import com.smsapp.exam.ExamDtos.RecordMarkRequest;
import com.smsapp.exam.ExamService.MarkResult;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock
    private ExamRepository examRepository;

    @Mock
    private ExamMarkRepository examMarkRepository;

    @Mock
    private ClassRepository classRepository;

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AuditService auditService;

    private final UUID classId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();
    private final UUID examId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    private ExamService service() {
        return new ExamService(examRepository, examMarkRepository, classRepository, subjectRepository,
                studentRepository, auditService);
    }

    private CreateExamRequest examRequest(String maxMarks) {
        return new CreateExamRequest(classId, subjectId, "Mid-term 2026", LocalDate.of(2026, 10, 1),
                new BigDecimal(maxMarks));
    }

    private void classAndSubjectExist() {
        lenient().when(classRepository.findById(classId)).thenReturn(Optional.of(new SchoolClass()));
        lenient().when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(new Subject()));
    }

    private Exam exam(String maxMarks) {
        Exam exam = new Exam();
        exam.setId(examId);
        exam.setMaxMarks(new BigDecimal(maxMarks));
        return exam;
    }

    @Test
    void createsExam() {
        classAndSubjectExist();
        when(examRepository.save(any(Exam.class))).thenAnswer(inv -> inv.getArgument(0));

        Exam created = service().createExam(examRequest("100"));

        assertThat(created.getClassId()).isEqualTo(classId);
        assertThat(created.getSubjectId()).isEqualTo(subjectId);
        assertThat(created.getName()).isEqualTo("Mid-term 2026");
        assertThat(created.getMaxMarks()).isEqualByComparingTo("100");
    }

    @Test
    void rejectsExamForNonexistentClassWith404() {
        when(classRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createExam(examRequest("100")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(examRepository, never()).save(any());
    }

    @Test
    void rejectsExamForNonexistentSubjectWith404() {
        when(classRepository.findById(classId)).thenReturn(Optional.of(new SchoolClass()));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createExam(examRequest("100")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(examRepository, never()).save(any());
    }

    @Test
    void rejectsNonPositiveMaxMarksWith400() {
        classAndSubjectExist();

        assertThatThrownBy(() -> service().createExam(examRequest("0")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void recordsANewMark() {
        when(examRepository.findById(examId)).thenReturn(Optional.of(exam("100")));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));
        when(examMarkRepository.findByExamIdAndStudentId(examId, studentId)).thenReturn(Optional.empty());
        when(examMarkRepository.save(any(ExamMark.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkResult result = service().recordMark(examId, new RecordMarkRequest(studentId, new BigDecimal("87.5")));

        assertThat(result.created()).isTrue();
        assertThat(result.mark().getExamId()).isEqualTo(examId);
        assertThat(result.mark().getStudentId()).isEqualTo(studentId);
        assertThat(result.mark().getMarksObtained()).isEqualByComparingTo("87.5");
    }

    @Test
    void correctsAnExistingMarkInPlaceInsteadOfDuplicating() {
        ExamMark existing = new ExamMark();
        existing.setId(UUID.randomUUID());
        existing.setExamId(examId);
        existing.setStudentId(studentId);
        existing.setMarksObtained(new BigDecimal("40"));
        when(examRepository.findById(examId)).thenReturn(Optional.of(exam("100")));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));
        when(examMarkRepository.findByExamIdAndStudentId(examId, studentId)).thenReturn(Optional.of(existing));
        when(examMarkRepository.save(any(ExamMark.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkResult result = service().recordMark(examId, new RecordMarkRequest(studentId, new BigDecimal("55")));

        assertThat(result.created()).isFalse();
        assertThat(result.mark().getId()).isEqualTo(existing.getId());
        assertThat(result.mark().getMarksObtained()).isEqualByComparingTo("55");
    }

    @Test
    void rejectsMarksExceedingMaxMarksWith400() {
        when(examRepository.findById(examId)).thenReturn(Optional.of(exam("100")));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));

        assertThatThrownBy(() -> service().recordMark(examId, new RecordMarkRequest(studentId, new BigDecimal("101"))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(examMarkRepository, never()).save(any());
    }

    @Test
    void rejectsMarksForNonexistentExamWith404() {
        when(examRepository.findById(examId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().recordMark(examId, new RecordMarkRequest(studentId, new BigDecimal("50"))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsMarksForNonexistentStudentWith404() {
        when(examRepository.findById(examId)).thenReturn(Optional.of(exam("100")));
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().recordMark(examId, new RecordMarkRequest(studentId, new BigDecimal("50"))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(examMarkRepository, never()).save(any());
    }

    @Test
    void listForClassRejectsANonexistentClassWith404() {
        when(classRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listForClass(classId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void gradebookRejectsANonexistentExamWith404() {
        when(examRepository.findById(examId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().gradebook(examId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void studentResultsRejectsANonexistentStudentWith404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().studentResults(studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
