package com.smsapp.frontoffice;

import com.smsapp.academics.ClassRepository;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.EnquiryDtos.CreateEnquiryRequest;
import com.smsapp.frontoffice.EnquiryDtos.EnquiryConversionResult;
import com.smsapp.frontoffice.EnquiryDtos.RecordFollowUpRequest;
import com.smsapp.frontoffice.EnquiryDtos.UpdateEnquiryRequest;
import com.smsapp.student.Student;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentService;
import com.smsapp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnquiryServiceTest {

    @Mock
    private AdmissionEnquiryRepository enquiryRepository;

    @Mock
    private EnquiryFollowUpRepository followUpRepository;

    @Mock
    private EnquirySourceRepository sourceRepository;

    @Mock
    private ClassRepository classRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StudentService studentService;

    @Mock
    private AuditService auditService;

    private final UUID staffUserId = UUID.randomUUID();

    private EnquiryService service() {
        return new EnquiryService(enquiryRepository, followUpRepository, sourceRepository, classRepository,
                userRepository, studentService, auditService);
    }

    private static AdmissionEnquiry enquiry(UUID id, String status) {
        AdmissionEnquiry e = new AdmissionEnquiry();
        e.setId(id);
        e.setEnquiryNumber("ENQ-000001");
        e.setApplicantName("Alex Applicant");
        e.setStatus(status);
        e.setArchived(false);
        return e;
    }

    @Test
    void createsEnquiryWithAGeneratedSequentialNumber() {
        when(enquiryRepository.nextEnquiryNumberSeq()).thenReturn(42L);
        when(enquiryRepository.save(any(AdmissionEnquiry.class))).thenAnswer(inv -> inv.getArgument(0));

        AdmissionEnquiry created = service().create(new CreateEnquiryRequest(
                "Alex Applicant", "Guardian Name", "+911234567890", "guardian@example.com",
                null, null, null, null, null));

        assertThat(created.getEnquiryNumber()).isEqualTo("ENQ-000042");
        assertThat(created.getStatus()).isEqualTo(EnquiryStatus.ACTIVE);
        assertThat(created.isArchived()).isFalse();
        assertThat(created.getEnquiryDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void rejectsCreateWithUnknownClassIdWith404() {
        UUID classId = UUID.randomUUID();
        when(classRepository.existsById(classId)).thenReturn(false);

        assertThatThrownBy(() -> service().create(new CreateEnquiryRequest(
                "Alex", null, null, null, classId, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(enquiryRepository, never()).save(any());
    }

    @Test
    void changeStatusRejectsUnknownStatusWith400() {
        UUID id = UUID.randomUUID();
        when(enquiryRepository.findById(id)).thenReturn(Optional.of(enquiry(id, EnquiryStatus.ACTIVE)));

        assertThatThrownBy(() -> service().changeStatus(id, "NOT_A_STATUS"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changeStatusRejectsUnknownEnquiryWith404() {
        UUID id = UUID.randomUUID();
        when(enquiryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().changeStatus(id, EnquiryStatus.LOST))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void recordFollowUpUpdatesTheEnquiryCacheAndMovesActiveToFollowUp() {
        UUID id = UUID.randomUUID();
        AdmissionEnquiry existing = enquiry(id, EnquiryStatus.ACTIVE);
        when(enquiryRepository.findById(id)).thenReturn(Optional.of(existing));
        when(followUpRepository.save(any(EnquiryFollowUp.class))).thenAnswer(inv -> inv.getArgument(0));
        when(enquiryRepository.save(any(AdmissionEnquiry.class))).thenAnswer(inv -> inv.getArgument(0));

        LocalDate next = LocalDate.now().plusDays(3);
        service().recordFollowUp(id, new RecordFollowUpRequest(LocalDate.now(), "call", "Spoke to parent", next),
                staffUserId);

        assertThat(existing.getStatus()).isEqualTo(EnquiryStatus.FOLLOW_UP);
        assertThat(existing.getFollowUpDate()).isEqualTo(next);
        assertThat(existing.getFollowUpNotes()).isEqualTo("Spoke to parent");
    }

    @Test
    void recordFollowUpRejectsUnknownTypeWith400() {
        UUID id = UUID.randomUUID();
        when(enquiryRepository.findById(id)).thenReturn(Optional.of(enquiry(id, EnquiryStatus.ACTIVE)));

        assertThatThrownBy(() -> service().recordFollowUp(id,
                new RecordFollowUpRequest(LocalDate.now(), "CARRIER_PIGEON", null, null), staffUserId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(followUpRepository, never()).save(any());
    }

    @Test
    void convertToStudentReusesStudentServiceAndLinksTheResult() {
        UUID enquiryId = UUID.randomUUID();
        AdmissionEnquiry existing = enquiry(enquiryId, EnquiryStatus.ACTIVE);
        when(enquiryRepository.findById(enquiryId)).thenReturn(Optional.of(existing));
        when(enquiryRepository.save(any(AdmissionEnquiry.class))).thenAnswer(inv -> inv.getArgument(0));

        Student createdStudent = new Student();
        createdStudent.setId(UUID.randomUUID());
        createdStudent.setFullName("Alex Applicant");
        createdStudent.setAdmissionNumber("ADM-1");
        createdStudent.setStatus("ACTIVE");
        when(studentService.create(any(CreateStudentRequest.class))).thenReturn(createdStudent);

        EnquiryConversionResult result = service().convertToStudent(enquiryId, sampleCreateStudentRequest());

        assertThat(existing.getStatus()).isEqualTo(EnquiryStatus.WON);
        assertThat(existing.getConvertedStudentId()).isEqualTo(createdStudent.getId());
        assertThat(result.student().id()).isEqualTo(createdStudent.getId());
        assertThat(result.enquiry().convertedStudentId()).isEqualTo(createdStudent.getId());
    }

    @Test
    void convertingAnAlreadyConvertedEnquiryReturns409AndNeverCreatesASecondStudent() {
        UUID enquiryId = UUID.randomUUID();
        AdmissionEnquiry existing = enquiry(enquiryId, EnquiryStatus.WON);
        existing.setConvertedStudentId(UUID.randomUUID());
        when(enquiryRepository.findById(enquiryId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service().convertToStudent(enquiryId, sampleCreateStudentRequest()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(studentService, never()).create(any());
    }

    private static CreateStudentRequest sampleCreateStudentRequest() {
        return new CreateStudentRequest(
                UUID.randomUUID(),
                // personal (10): firstName, middleName, lastName, gender, dateOfBirth, bloodGroup, nationality, religion, motherTongue, category
                "Alex", null, "Applicant", "MALE", LocalDate.of(2015, 4, 1), null, null, null, null, null,
                // admission (12): admissionNumber, rollNumber, enrollmentNumber, admissionDate, sectionId, previousSchoolName, previousSchoolClass, previousSchoolAdmissionNumber, previousSchoolAddress, transferCertificateNumber, admissionSource, rteStatus
                "ADM-CONVERTED-1", null, null, LocalDate.now(), null, null, null, null, null, null, null, false,
                // guardian (7)
                "Guardian Name", "FATHER", "+911234567890", null, null, null, null,
                // father/mother (8)
                null, null, null, null, null, null, null, null,
                // emergency (5)
                null, null, null, null, null,
                // current address (6)
                null, null, null, null, null, null,
                // permanent address (7)
                null, null, null, null, null, null, null,
                // familyId (1)
                null,
                // comm prefs (4)
                null, null, null, null);
    }

    @Test
    void updateRejectsUnknownEnquiryWith404() {
        UUID id = UUID.randomUUID();
        when(enquiryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(id, new UpdateEnquiryRequest(
                "Alex", null, null, null, null, null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void summaryComputesRealCountsFromRepositories() {
        when(enquiryRepository.countByArchivedFalse()).thenReturn(10L);
        when(enquiryRepository.countByStatusAndArchivedFalse(EnquiryStatus.ACTIVE)).thenReturn(4L);
        when(enquiryRepository.countByStatusAndArchivedFalse(EnquiryStatus.FOLLOW_UP)).thenReturn(2L);
        when(enquiryRepository.countByFollowUpDateLessThanEqualAndArchivedFalseAndStatusNotIn(any(), any()))
                .thenReturn(3L);
        when(enquiryRepository.countByConvertedStudentIdIsNotNull()).thenReturn(1L);
        when(enquiryRepository.countByStatusAndArchivedFalse(EnquiryStatus.LOST)).thenReturn(2L);
        when(sourceRepository.findAll()).thenReturn(List.of());
        when(classRepository.findAll()).thenReturn(List.of());
        when(enquiryRepository.countBySource()).thenReturn(List.of());
        when(enquiryRepository.countByClass()).thenReturn(List.of());
        when(enquiryRepository.findTop5ByArchivedFalseOrderByCreatedAtDesc()).thenReturn(List.of());

        var summary = service().summary();

        assertThat(summary.totalEnquiries()).isEqualTo(10);
        assertThat(summary.activeEnquiries()).isEqualTo(6);
        assertThat(summary.followUpsDue()).isEqualTo(3);
        assertThat(summary.converted()).isEqualTo(1);
        assertThat(summary.lost()).isEqualTo(2);
    }

    // Silence "unused parameter" for Specification-based search not directly asserted here --
    // covered end-to-end by EnquiryIntegrationTest instead (real Postgres, real predicate composition).
    @Test
    void searchDelegatesToTheRepositorySpecification() {
        Page<AdmissionEnquiry> page = Page.empty();
        when(enquiryRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AdmissionEnquiry>>any(),
                any(Pageable.class))).thenReturn(page);

        Page<AdmissionEnquiry> result = service().search(null, null, null, null, null, null, null, false,
                Pageable.unpaged());

        assertThat(result).isSameAs(page);
    }
}
