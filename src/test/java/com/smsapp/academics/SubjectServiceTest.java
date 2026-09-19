package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.CreateSubjectRequest;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectServiceTest {

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private ClassSubjectRepository classSubjectRepository;

    @Mock
    private ClassRepository classRepository;

    @Mock
    private SchoolRepository schoolRepository;

    @Mock
    private AuditService auditService;

    private final UUID schoolId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();

    private SubjectService service() {
        return new SubjectService(subjectRepository, classSubjectRepository, classRepository, schoolRepository,
                auditService);
    }

    private Subject subject() {
        Subject subject = new Subject();
        subject.setId(subjectId);
        subject.setSchoolId(schoolId);
        subject.setName("Mathematics");
        return subject;
    }

    private SchoolClass schoolClass() {
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.setId(classId);
        return schoolClass;
    }

    @Test
    void createsSubject() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(subjectRepository.existsBySchoolIdAndName(schoolId, "Mathematics")).thenReturn(false);
        when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> inv.getArgument(0));

        Subject created = service().create(new CreateSubjectRequest(schoolId, "  Mathematics  "));

        assertThat(created.getSchoolId()).isEqualTo(schoolId);
        assertThat(created.getName()).isEqualTo("Mathematics");
    }

    @Test
    void rejectsSubjectForNonexistentSchoolWith404() {
        when(schoolRepository.existsById(schoolId)).thenReturn(false);

        assertThatThrownBy(() -> service().create(new CreateSubjectRequest(schoolId, "Mathematics")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(subjectRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateSubjectNameWith409() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(subjectRepository.existsBySchoolIdAndName(schoolId, "Mathematics")).thenReturn(true);

        assertThatThrownBy(() -> service().create(new CreateSubjectRequest(schoolId, "Mathematics")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(subjectRepository, never()).save(any());
    }

    @Test
    void assignsSubjectToAnExistingClass() {
        when(classRepository.findById(classId)).thenReturn(Optional.of(schoolClass()));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject()));
        when(classSubjectRepository.existsByClassIdAndSubjectId(classId, subjectId)).thenReturn(false);
        when(classSubjectRepository.save(any(ClassSubject.class))).thenAnswer(inv -> inv.getArgument(0));

        Subject result = service().assignToClass(classId, subjectId);

        assertThat(result.getId()).isEqualTo(subjectId);
        verify(classSubjectRepository).save(any(ClassSubject.class));
    }

    @Test
    void rejectsAssignmentWhenClassDoesNotExistWith404() {
        when(classRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignToClass(classId, subjectId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(classSubjectRepository, never()).save(any());
    }

    @Test
    void rejectsAssignmentWhenSubjectDoesNotExistWith404() {
        when(classRepository.findById(classId)).thenReturn(Optional.of(schoolClass()));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignToClass(classId, subjectId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(classSubjectRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateAssignmentWith409() {
        when(classRepository.findById(classId)).thenReturn(Optional.of(schoolClass()));
        when(subjectRepository.findById(subjectId)).thenReturn(Optional.of(subject()));
        when(classSubjectRepository.existsByClassIdAndSubjectId(classId, subjectId)).thenReturn(true);

        assertThatThrownBy(() -> service().assignToClass(classId, subjectId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(classSubjectRepository, never()).save(any());
    }

    @Test
    void listForClassRejectsANonexistentClassWith404() {
        when(classRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listForClass(classId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
