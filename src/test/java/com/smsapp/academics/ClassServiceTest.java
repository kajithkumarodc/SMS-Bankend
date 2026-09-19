package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.ClassResponse;
import com.smsapp.academics.AcademicsDtos.CreateClassRequest;
import com.smsapp.academics.AcademicsDtos.CreateSectionRequest;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

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
class ClassServiceTest {

    @Mock
    private ClassRepository classRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private SchoolRepository schoolRepository;

    @Mock
    private AuditService auditService;

    private static final String GRADE_5 = "Grade 5";

    private final UUID schoolId = UUID.randomUUID();

    private ClassService service() {
        return new ClassService(classRepository, sectionRepository, schoolRepository, auditService);
    }

    private SchoolClass schoolClass(UUID id, String name) {
        SchoolClass c = new SchoolClass();
        c.setId(id);
        c.setSchoolId(schoolId);
        c.setName(name);
        return c;
    }

    private Section section(UUID id, UUID classId, String name) {
        Section s = new Section();
        s.setId(id);
        s.setClassId(classId);
        s.setName(name);
        return s;
    }

    @Test
    void createsClass() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(classRepository.existsBySchoolIdAndName(schoolId, GRADE_5)).thenReturn(false);
        when(classRepository.save(any(SchoolClass.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolClass created = service().createClass(new CreateClassRequest(schoolId, "  Grade 5  "));

        assertThat(created.getSchoolId()).isEqualTo(schoolId);
        assertThat(created.getName()).isEqualTo(GRADE_5);
    }

    @Test
    void rejectsClassForNonexistentSchoolWith404() {
        when(schoolRepository.existsById(schoolId)).thenReturn(false);

        assertThatThrownBy(() -> service().createClass(new CreateClassRequest(schoolId, GRADE_5)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(classRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateClassNameWith409() {
        when(schoolRepository.existsById(schoolId)).thenReturn(true);
        when(classRepository.existsBySchoolIdAndName(schoolId, GRADE_5)).thenReturn(true);

        assertThatThrownBy(() -> service().createClass(new CreateClassRequest(schoolId, GRADE_5)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(classRepository, never()).save(any());
    }

    @Test
    void createsSectionUnderAnExistingClass() {
        UUID classId = UUID.randomUUID();
        when(classRepository.findById(classId)).thenReturn(Optional.of(schoolClass(classId, GRADE_5)));
        when(sectionRepository.existsByClassIdAndName(classId, "A")).thenReturn(false);
        when(sectionRepository.save(any(Section.class))).thenAnswer(inv -> inv.getArgument(0));

        Section created = service().createSection(classId, new CreateSectionRequest(" A "));

        assertThat(created.getClassId()).isEqualTo(classId);
        assertThat(created.getName()).isEqualTo("A");
    }

    @Test
    void rejectsSectionUnderANonexistentClassWith404() {
        UUID classId = UUID.randomUUID();
        when(classRepository.findById(classId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createSection(classId, new CreateSectionRequest("A")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(sectionRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateSectionNameWith409() {
        UUID classId = UUID.randomUUID();
        when(classRepository.findById(classId)).thenReturn(Optional.of(schoolClass(classId, GRADE_5)));
        when(sectionRepository.existsByClassIdAndName(classId, "A")).thenReturn(true);

        assertThatThrownBy(() -> service().createSection(classId, new CreateSectionRequest("A")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listWithSectionsNestsSectionsUnderTheirClass() {
        UUID class5 = UUID.randomUUID();
        UUID class6 = UUID.randomUUID();
        when(classRepository.findAllByOrderByName())
                .thenReturn(List.of(schoolClass(class5, GRADE_5), schoolClass(class6, "Grade 6")));
        when(sectionRepository.findAllByOrderByName()).thenReturn(List.of(
                section(UUID.randomUUID(), class5, "A"),
                section(UUID.randomUUID(), class5, "B")));

        List<ClassResponse> result = service().listWithSections();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).sections()).extracting(AcademicsDtos.SectionResponse::name)
                .containsExactly("A", "B");
        assertThat(result.get(1).sections()).isEmpty();
    }
}
