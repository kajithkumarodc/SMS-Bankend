package com.smsapp.student;

import com.smsapp.academics.SectionRepository;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentDtos.UpdateStudentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private SchoolRepository schoolRepository;

    @Mock
    private SectionRepository sectionRepository;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID schoolId = UUID.randomUUID();

    private StudentService service() {
        return new StudentService(studentRepository, schoolRepository, sectionRepository);
    }

    private CreateStudentRequest request(String fullName, String admissionNumber) {
        return new CreateStudentRequest(schoolId, fullName, admissionNumber,
                LocalDate.of(2015, 6, 1), "Guardian", "+1000000000");
    }

    @Test
    void createsActiveStudentScopedToTenantWhenValid() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(true);
        when(studentRepository.existsByTenantIdAndAdmissionNumber(tenantId, "ADM-1")).thenReturn(false);
        when(studentRepository.saveAndFlush(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        service().create(tenantId, request("  Alice Doe  ", " ADM-1 "));

        ArgumentCaptor<Student> saved = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getValue().getSchoolId()).isEqualTo(schoolId);
        assertThat(saved.getValue().getFullName()).isEqualTo("Alice Doe");
        assertThat(saved.getValue().getAdmissionNumber()).isEqualTo("ADM-1");
        assertThat(saved.getValue().getStatus()).isEqualTo(StudentStatus.ACTIVE);
    }

    @Test
    void rejectsSchoolThatDoesNotBelongToTenantWith404() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> service().create(tenantId, request("Alice", "ADM-1")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDuplicateAdmissionNumberInTenantWith409() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(true);
        when(studentRepository.existsByTenantIdAndAdmissionNumber(tenantId, "ADM-1")).thenReturn(true);

        assertThatThrownBy(() -> service().create(tenantId, request("Alice", "ADM-1")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(studentRepository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentInsertRaceIntoA409NotARawDbError() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(true);
        when(studentRepository.existsByTenantIdAndAdmissionNumber(tenantId, "ADM-1")).thenReturn(false);
        when(studentRepository.saveAndFlush(any(Student.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service().create(tenantId, request("Alice", "ADM-1")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getReportsMissingStudentAs404NotForbidden() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(tenantId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Student existing(UUID studentId) {
        Student student = new Student();
        student.setId(studentId);
        student.setTenantId(tenantId);
        student.setSchoolId(schoolId);
        student.setFullName("Old Name");
        student.setAdmissionNumber("ADM-KEEP");
        student.setGuardianName("Old Guardian");
        student.setStatus(StudentStatus.ACTIVE);
        return student;
    }

    @Test
    void updateChangesEditableFieldsButKeepsAdmissionNumber() {
        UUID studentId = UUID.randomUUID();
        Student student = existing(studentId);
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.of(student));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student result = service().update(tenantId, studentId,
                new UpdateStudentRequest("  New Name  ", "  New Guardian  ", "  ", StudentStatus.INACTIVE));

        assertThat(result.getFullName()).isEqualTo("New Name");
        assertThat(result.getGuardianName()).isEqualTo("New Guardian");
        assertThat(result.getGuardianContact()).isNull();
        assertThat(result.getStatus()).isEqualTo(StudentStatus.INACTIVE);
        assertThat(result.getAdmissionNumber()).isEqualTo("ADM-KEEP");
    }

    @Test
    void updateReportsMissingStudentAs404() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(tenantId, studentId,
                new UpdateStudentRequest("Name", null, null, StudentStatus.ACTIVE)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void updateRejectsUnknownStatusWith400() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.of(existing(studentId)));

        assertThatThrownBy(() -> service().update(tenantId, studentId,
                new UpdateStudentRequest("Name", null, null, "GRADUATED")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void changeStatusDeactivatesWithoutDeleting() {
        UUID studentId = UUID.randomUUID();
        Student student = existing(studentId);
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.of(student));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student result = service().changeStatus(tenantId, studentId, "inactive");

        assertThat(result.getStatus()).isEqualTo(StudentStatus.INACTIVE);
        verify(studentRepository, never()).delete(any());
    }

    @Test
    void changeStatusReportsMissingStudentAs404() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().changeStatus(tenantId, studentId, StudentStatus.INACTIVE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignSectionSetsSectionIdWhenBothStudentAndSectionAreInTenant() {
        UUID studentId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        Student student = existing(studentId);
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.of(student));
        when(sectionRepository.existsByIdAndTenantId(sectionId, tenantId)).thenReturn(true);
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student result = service().assignSection(tenantId, studentId, sectionId);

        assertThat(result.getSectionId()).isEqualTo(sectionId);
    }

    @Test
    void assignSectionReportsAnotherTenantsSectionAs404() {
        UUID studentId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.of(existing(studentId)));
        when(sectionRepository.existsByIdAndTenantId(sectionId, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> service().assignSection(tenantId, studentId, sectionId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void assignSectionReportsMissingStudentAs404() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignSection(tenantId, studentId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
