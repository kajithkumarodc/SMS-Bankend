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

    private final UUID tenantId = UUID.randomUUID();
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
        subject.setTenantId(tenantId);
        subject.setSchoolId(schoolId);
        subject.setName("Mathematics");
        return subject;
    }

    private SchoolClass schoolClass() {
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.setId(classId);
        schoolClass.setTenantId(tenantId);
        return schoolClass;
    }

    @Test
    void createsSubjectScopedToTenant() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(true);
        when(subjectRepository.existsByTenantIdAndSchoolIdAndName(tenantId, schoolId, "Mathematics")).thenReturn(false);
        when(subjectRepository.save(any(Subject.class))).thenAnswer(inv -> inv.getArgument(0));

        Subject created = service().create(tenantId, new CreateSubjectRequest(schoolId, "  Mathematics  "));

        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getSchoolId()).isEqualTo(schoolId);
        assertThat(created.getName()).isEqualTo("Mathematics");
    }

    @Test
    void rejectsSubjectForSchoolOutsideTenantWith404() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> service().create(tenantId, new CreateSubjectRequest(schoolId, "Mathematics")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(subjectRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateSubjectNameWith409() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(true);
        when(subjectRepository.existsByTenantIdAndSchoolIdAndName(tenantId, schoolId, "Mathematics")).thenReturn(true);

        assertThatThrownBy(() -> service().create(tenantId, new CreateSubjectRequest(schoolId, "Mathematics")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(subjectRepository, never()).save(any());
    }

    @Test
    void assignsSubjectToAnOwnedClass() {
        when(classRepository.findByIdAndTenantId(classId, tenantId)).thenReturn(Optional.of(schoolClass()));
        when(subjectRepository.findByIdAndTenantId(subjectId, tenantId)).thenReturn(Optional.of(subject()));
        when(classSubjectRepository.existsByTenantIdAndClassIdAndSubjectId(tenantId, classId, subjectId))
                .thenReturn(false);
        when(classSubjectRepository.save(any(ClassSubject.class))).thenAnswer(inv -> inv.getArgument(0));

        Subject result = service().assignToClass(tenantId, classId, subjectId);

        assertThat(result.getId()).isEqualTo(subjectId);
        verify(classSubjectRepository).save(any(ClassSubject.class));
    }

    @Test
    void rejectsAssignmentWhenClassNotInTenantWith404() {
        when(classRepository.findByIdAndTenantId(classId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignToClass(tenantId, classId, subjectId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(classSubjectRepository, never()).save(any());
    }

    @Test
    void rejectsAssignmentWhenSubjectNotInTenantWith404() {
        when(classRepository.findByIdAndTenantId(classId, tenantId)).thenReturn(Optional.of(schoolClass()));
        when(subjectRepository.findByIdAndTenantId(subjectId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignToClass(tenantId, classId, subjectId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(classSubjectRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateAssignmentWith409() {
        when(classRepository.findByIdAndTenantId(classId, tenantId)).thenReturn(Optional.of(schoolClass()));
        when(subjectRepository.findByIdAndTenantId(subjectId, tenantId)).thenReturn(Optional.of(subject()));
        when(classSubjectRepository.existsByTenantIdAndClassIdAndSubjectId(tenantId, classId, subjectId))
                .thenReturn(true);

        assertThatThrownBy(() -> service().assignToClass(tenantId, classId, subjectId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(classSubjectRepository, never()).save(any());
    }

    @Test
    void listForClassRejectsAnotherTenantsClassWith404() {
        when(classRepository.findByIdAndTenantId(classId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().listForClass(tenantId, classId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
