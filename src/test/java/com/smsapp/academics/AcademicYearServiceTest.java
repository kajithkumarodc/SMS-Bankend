package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.CreateAcademicYearRequest;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.student.AcademicHistoryService;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcademicYearServiceTest {

    @Mock
    private AcademicYearRepository academicYearRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AcademicHistoryService academicHistoryService;

    @Mock
    private AuditService auditService;

    private AcademicYearService service() {
        return new AcademicYearService(academicYearRepository, sectionRepository, studentRepository,
                academicHistoryService, auditService);
    }

    @Test
    void createsAcademicYear() {
        when(academicYearRepository.existsByName("2025-2026")).thenReturn(false);
        when(academicYearRepository.saveAndFlush(any(AcademicYear.class))).thenAnswer(inv -> inv.getArgument(0));

        AcademicYear created = service().create(new CreateAcademicYearRequest(
                "2025-2026", LocalDate.of(2025, 6, 1), LocalDate.of(2026, 4, 30)));

        assertThat(created.getName()).isEqualTo("2025-2026");
        assertThat(created.isCurrent()).isFalse();
    }

    @Test
    void rejectsEndDateNotAfterStartDateWith400() {
        assertThatThrownBy(() -> service().create(new CreateAcademicYearRequest(
                "2025-2026", LocalDate.of(2026, 4, 30), LocalDate.of(2025, 6, 1))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsDuplicateNameWith409() {
        when(academicYearRepository.existsByName("2025-2026")).thenReturn(true);

        assertThatThrownBy(() -> service().create(new CreateAcademicYearRequest(
                "2025-2026", LocalDate.of(2025, 6, 1), LocalDate.of(2026, 4, 30))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void setCurrentUnmarksThePreviousCurrentYear() {
        UUID previousId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        AcademicYear previous = year(previousId, "2024-2025", true);
        AcademicYear next = year(newId, "2025-2026", false);
        when(academicYearRepository.findById(newId)).thenReturn(Optional.of(next));
        when(academicYearRepository.findByCurrentTrue()).thenReturn(Optional.of(previous));
        when(academicYearRepository.save(any(AcademicYear.class))).thenAnswer(inv -> inv.getArgument(0));

        AcademicYear result = service().setCurrent(newId);

        assertThat(result.isCurrent()).isTrue();
        assertThat(previous.isCurrent()).isFalse();
    }

    @Test
    void promoteStudentsRejectsUnknownSourceSectionWith404() {
        UUID fromSection = UUID.randomUUID();
        when(sectionRepository.existsById(fromSection)).thenReturn(false);

        assertThatThrownBy(() -> service().promoteStudents(
                fromSection, UUID.randomUUID(), null, null, Set.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void promoteStudentsMovesOnlyStudentsCurrentlyInTheSourceSectionAndAreActive() {
        UUID fromSection = UUID.randomUUID();
        UUID toSection = UUID.randomUUID();
        UUID inSection = UUID.randomUUID();
        UUID elsewhere = UUID.randomUUID();
        when(sectionRepository.existsById(fromSection)).thenReturn(true);
        Section destination = new Section();
        destination.setId(toSection);
        when(sectionRepository.findById(toSection)).thenReturn(Optional.of(destination));
        when(academicYearRepository.findByCurrentTrue()).thenReturn(Optional.empty());

        Student studentInSection = student(inSection, fromSection);
        Student studentElsewhere = student(elsewhere, UUID.randomUUID());
        when(studentRepository.findAllById(Set.of(inSection, elsewhere)))
                .thenReturn(List.of(studentInSection, studentElsewhere));

        AcademicYearService.PromotionOutcome outcome = service().promoteStudents(
                fromSection, toSection, null, null, Set.of(inSection, elsewhere));

        assertThat(outcome.promotedCount()).isEqualTo(1);
        assertThat(studentInSection.getSectionId()).isEqualTo(toSection);
        assertThat(studentElsewhere.getSectionId()).isNotEqualTo(toSection);
        assertThat(outcome.results()).hasSize(2);
        assertThat(outcome.results().stream().filter(AcademicYearService.PromotionResult::promoted)).hasSize(1);
    }

    @Test
    void promoteStudentsRejectsSameSourceAndDestinationSectionWith400() {
        UUID section = UUID.randomUUID();

        assertThatThrownBy(() -> service().promoteStudents(
                section, section, null, null, Set.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void promoteStudentsRejectsDestinationSectionNotBelongingToDestinationClassWith400() {
        UUID fromSection = UUID.randomUUID();
        UUID toSection = UUID.randomUUID();
        UUID wrongClassId = UUID.randomUUID();
        when(sectionRepository.existsById(fromSection)).thenReturn(true);
        Section destination = new Section();
        destination.setId(toSection);
        destination.setClassId(UUID.randomUUID());
        when(sectionRepository.findById(toSection)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> service().promoteStudents(
                fromSection, toSection, wrongClassId, null, Set.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void promoteStudentsSkipsArchivedStudentsWithAReason() {
        UUID fromSection = UUID.randomUUID();
        UUID toSection = UUID.randomUUID();
        UUID archivedId = UUID.randomUUID();
        when(sectionRepository.existsById(fromSection)).thenReturn(true);
        Section destination = new Section();
        destination.setId(toSection);
        when(sectionRepository.findById(toSection)).thenReturn(Optional.of(destination));
        when(academicYearRepository.findByCurrentTrue()).thenReturn(Optional.empty());

        Student graduated = student(archivedId, fromSection);
        graduated.setStatus("GRADUATED");
        when(studentRepository.findAllById(Set.of(archivedId))).thenReturn(List.of(graduated));

        AcademicYearService.PromotionOutcome outcome = service().promoteStudents(
                fromSection, toSection, null, null, Set.of(archivedId));

        assertThat(outcome.promotedCount()).isZero();
        assertThat(outcome.results()).singleElement().satisfies(result -> {
            assertThat(result.promoted()).isFalse();
            assertThat(result.reason()).contains("not active");
        });
    }

    @Test
    void promoteStudentsSkipsStudentsAlreadyPromotedForTheTargetYear() {
        UUID fromSection = UUID.randomUUID();
        UUID toSection = UUID.randomUUID();
        UUID targetYear = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        when(sectionRepository.existsById(fromSection)).thenReturn(true);
        Section destination = new Section();
        destination.setId(toSection);
        when(sectionRepository.findById(toSection)).thenReturn(Optional.of(destination));
        when(academicYearRepository.existsById(targetYear)).thenReturn(true);
        when(academicHistoryService.alreadyPlacedForYear(studentId, targetYear)).thenReturn(true);

        Student alreadyPromoted = student(studentId, fromSection);
        when(studentRepository.findAllById(Set.of(studentId))).thenReturn(List.of(alreadyPromoted));

        AcademicYearService.PromotionOutcome outcome = service().promoteStudents(
                fromSection, toSection, null, targetYear, Set.of(studentId));

        assertThat(outcome.promotedCount()).isZero();
        assertThat(outcome.results()).singleElement().satisfies(result -> {
            assertThat(result.promoted()).isFalse();
            assertThat(result.reason()).contains("Already promoted");
        });
        assertThat(alreadyPromoted.getSectionId()).isEqualTo(fromSection);
    }

    @Test
    void promoteStudentsRejectsUnknownTargetAcademicYearWith404() {
        UUID fromSection = UUID.randomUUID();
        UUID toSection = UUID.randomUUID();
        UUID targetYear = UUID.randomUUID();
        when(sectionRepository.existsById(fromSection)).thenReturn(true);
        Section destination = new Section();
        destination.setId(toSection);
        when(sectionRepository.findById(toSection)).thenReturn(Optional.of(destination));
        when(academicYearRepository.existsById(targetYear)).thenReturn(false);

        assertThatThrownBy(() -> service().promoteStudents(
                fromSection, toSection, null, targetYear, Set.of(UUID.randomUUID())))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static AcademicYear year(UUID id, String name, boolean current) {
        AcademicYear y = new AcademicYear();
        y.setId(id);
        y.setName(name);
        y.setCurrent(current);
        return y;
    }

    private static Student student(UUID id, UUID sectionId) {
        Student s = new Student();
        s.setId(id);
        s.setSectionId(sectionId);
        s.setStatus("ACTIVE");
        return s;
    }
}
