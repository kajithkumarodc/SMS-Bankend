package com.smsapp.student;

import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
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

    private final UUID tenantId = UUID.randomUUID();
    private final UUID schoolId = UUID.randomUUID();

    private StudentService service() {
        return new StudentService(studentRepository, schoolRepository);
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
}
