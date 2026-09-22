package com.smsapp.student;

import com.smsapp.academics.SectionRepository;
import com.smsapp.audit.AuditService;
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

    @Mock
    private AcademicHistoryService academicHistoryService;

    @Mock
    private AuditService auditService;

    private final UUID schoolId = UUID.randomUUID();

    private StudentService service() {
        return new StudentService(studentRepository, schoolRepository, sectionRepository, academicHistoryService,
                auditService);
    }

    /** Minimal create request: schoolId/firstName/lastName/dateOfBirth/admissionNumber, everything else null. */
    private CreateStudentRequest request(String firstName, String lastName, String admissionNumber) {
        return new CreateStudentRequest(
                schoolId,
                // personal (10): firstName, middleName, lastName, gender, dateOfBirth, bloodGroup, nationality, religion, motherTongue, category
                firstName, null, lastName, null, LocalDate.of(2015, 6, 1), null, null, null, null, null,
                // admission (12): admissionNumber, rollNumber, enrollmentNumber, admissionDate, sectionId, previousSchoolName, previousSchoolClass, previousSchoolAdmissionNumber, previousSchoolAddress, transferCertificateNumber, admissionSource, rteStatus
                admissionNumber, null, null, null, null, null, null, null, null, null, null, false,
                // guardian (7): guardianName, guardianRelationship, guardianPhone, guardianAlternatePhone, guardianEmail, guardianOccupation, guardianContact
                "Guardian", null, null, null, null, null, "+1000000000",
                // father/mother (8)
                null, null, null, null, null, null, null, null,
                // emergency (5)
                null, null, null, null, null,
                // current address (6)
                null, null, null, null, null, null,
                // permanent address (7): permanentSameAsCurrentAddress, line1, line2, city, state, country, pincode
                null, null, null, null, null, null, null,
                // familyId (1)
                null,
                // comm prefs (4)
                null, null, null, null);
    }

    private UpdateStudentRequest updateRequest(String firstName, String lastName, String guardianName, String status) {
        return new UpdateStudentRequest(
                // personal (10): firstName, middleName, lastName, gender, dateOfBirth, bloodGroup, nationality, religion, motherTongue, category
                firstName, null, lastName, null, null, null, null, null, null, null,
                // admission (10): rollNumber, enrollmentNumber, admissionDate, previousSchoolName, previousSchoolClass, previousSchoolAdmissionNumber, previousSchoolAddress, transferCertificateNumber, admissionSource, rteStatus
                null, null, null, null, null, null, null, null, null, false,
                // guardian (7): guardianName, guardianRelationship, guardianPhone, guardianAlternatePhone, guardianEmail, guardianOccupation, guardianContact
                guardianName, null, null, null, null, null, null,
                // father/mother (8)
                null, null, null, null, null, null, null, null,
                // emergency (5)
                null, null, null, null, null,
                // current address (6)
                null, null, null, null, null, null,
                // permanent address (7): permanentSameAsCurrentAddress, line1, line2, city, state, country, pincode
                null, null, null, null, null, null, null,
                // familyId (1)
                null,
                // comm prefs (4)
                null, null, null, null,
                // status (1)
                status);
    }

    @Test
    void createsActiveStudentWhenValid() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(studentRepository.existsByAdmissionNumber("ADM-1")).thenReturn(false);
        when(studentRepository.saveAndFlush(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        service().create(request("  Alice  ", "  Doe  ", " ADM-1 "));

        ArgumentCaptor<Student> saved = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSchoolId()).isEqualTo(schoolId);
        assertThat(saved.getValue().getFirstName()).isEqualTo("Alice");
        assertThat(saved.getValue().getLastName()).isEqualTo("Doe");
        assertThat(saved.getValue().getFullName()).isEqualTo("Alice Doe");
        assertThat(saved.getValue().getAdmissionNumber()).isEqualTo("ADM-1");
        assertThat(saved.getValue().getStatus()).isEqualTo(StudentStatus.ACTIVE);
    }

    @Test
    void rejectsNonexistentSchoolWith404() {
        when(schoolRepository.existsById(schoolId)).thenReturn(false);

        assertThatThrownBy(() -> service().create(request("Alice", "Doe", "ADM-1")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsDuplicateAdmissionNumberWith409() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(studentRepository.existsByAdmissionNumber("ADM-1")).thenReturn(true);

        assertThatThrownBy(() -> service().create(request("Alice", "Doe", "ADM-1")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(studentRepository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentInsertRaceIntoA409NotARawDbError() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(studentRepository.existsByAdmissionNumber("ADM-1")).thenReturn(false);
        when(studentRepository.saveAndFlush(any(Student.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service().create(request("Alice", "Doe", "ADM-1")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getReportsMissingStudentAs404NotForbidden() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Student existing(UUID studentId) {
        Student student = new Student();
        student.setId(studentId);
        student.setSchoolId(schoolId);
        student.setFirstName("Old");
        student.setLastName("Name");
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
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student result = service().update(studentId,
                updateRequest("  New  ", "  Name  ", "  New Guardian  ", StudentStatus.INACTIVE));

        assertThat(result.getFullName()).isEqualTo("New Name");
        assertThat(result.getGuardianName()).isEqualTo("New Guardian");
        assertThat(result.getStatus()).isEqualTo(StudentStatus.INACTIVE);
        assertThat(result.getAdmissionNumber()).isEqualTo("ADM-KEEP");
    }

    @Test
    void updateReportsMissingStudentAs404() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(studentId, updateRequest("Name", "Two", null, StudentStatus.ACTIVE)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void updateRejectsUnknownStatusWith400() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(existing(studentId)));

        assertThatThrownBy(() -> service().update(studentId, updateRequest("Name", "Two", null, "NOT_A_STATUS")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void updateAcceptsEveryNewStatusValue() {
        for (String status : new String[] {StudentStatus.GRADUATED, StudentStatus.LEFT_SCHOOL, StudentStatus.TRANSFERRED}) {
            UUID studentId = UUID.randomUUID();
            when(studentRepository.findById(studentId)).thenReturn(Optional.of(existing(studentId)));
            when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

            Student result = service().update(studentId, updateRequest("Name", "Two", null, status));

            assertThat(result.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void changeStatusDeactivatesWithoutDeleting() {
        UUID studentId = UUID.randomUUID();
        Student student = existing(studentId);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student result = service().changeStatus(studentId, "inactive");

        assertThat(result.getStatus()).isEqualTo(StudentStatus.INACTIVE);
        verify(studentRepository, never()).delete(any(Student.class));
    }

    @Test
    void changeStatusReportsMissingStudentAs404() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().changeStatus(studentId, StudentStatus.INACTIVE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignSectionSetsSectionIdWhenBothStudentAndSectionExist() {
        UUID studentId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        Student student = existing(studentId);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(sectionRepository.existsById(sectionId)).thenReturn(true);
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student result = service().assignSection(studentId, sectionId);

        assertThat(result.getSectionId()).isEqualTo(sectionId);
        verify(academicHistoryService).record(studentId, sectionId, AcademicChangeReason.MANUAL_ASSIGNMENT);
    }

    @Test
    void assignSectionReportsANonexistentSectionAs404() {
        UUID studentId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(existing(studentId)));
        when(sectionRepository.existsById(sectionId)).thenReturn(false);

        assertThatThrownBy(() -> service().assignSection(studentId, sectionId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void assignSectionReportsMissingStudentAs404() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignSection(studentId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listInSectionReportsANonexistentSectionAs404() {
        UUID sectionId = UUID.randomUUID();
        when(sectionRepository.existsById(sectionId)).thenReturn(false);

        assertThatThrownBy(() -> service().listInSection(sectionId, org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void siblingsIsEmptyWhenStudentHasNoFamilyLink() {
        UUID studentId = UUID.randomUUID();
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(existing(studentId)));

        assertThat(service().siblings(studentId)).isEmpty();
    }

    @Test
    void linkSiblingCreatesASharedFamilyIdWhenNeitherHasOne() {
        UUID studentId = UUID.randomUUID();
        UUID siblingId = UUID.randomUUID();
        Student student = existing(studentId);
        Student sibling = existing(siblingId);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(studentRepository.findById(siblingId)).thenReturn(Optional.of(sibling));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student result = service().linkSibling(studentId, siblingId);

        assertThat(result.getFamilyId()).isNotNull();
        assertThat(result.getFamilyId()).isEqualTo(sibling.getFamilyId());
    }

    @Test
    void linkSiblingRejectsLinkingToSelfWith400() {
        UUID studentId = UUID.randomUUID();

        assertThatThrownBy(() -> service().linkSibling(studentId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
