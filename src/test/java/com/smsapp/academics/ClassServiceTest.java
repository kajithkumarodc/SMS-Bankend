package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.ClassResponse;
import com.smsapp.academics.AcademicsDtos.CreateClassRequest;
import com.smsapp.academics.AcademicsDtos.CreateSectionRequest;
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

    private final UUID tenantId = UUID.randomUUID();
    private final UUID schoolId = UUID.randomUUID();

    private ClassService service() {
        return new ClassService(classRepository, sectionRepository, schoolRepository);
    }

    private SchoolClass schoolClass(UUID id, String name) {
        SchoolClass c = new SchoolClass();
        c.setId(id);
        c.setTenantId(tenantId);
        c.setSchoolId(schoolId);
        c.setName(name);
        return c;
    }

    private Section section(UUID id, UUID classId, String name) {
        Section s = new Section();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setClassId(classId);
        s.setName(name);
        return s;
    }

    @Test
    void createsClassScopedToTenant() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(true);
        when(classRepository.existsByTenantIdAndSchoolIdAndName(tenantId, schoolId, "Grade 5")).thenReturn(false);
        when(classRepository.save(any(SchoolClass.class))).thenAnswer(inv -> inv.getArgument(0));

        SchoolClass created = service().createClass(tenantId, new CreateClassRequest(schoolId, "  Grade 5  "));

        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getSchoolId()).isEqualTo(schoolId);
        assertThat(created.getName()).isEqualTo("Grade 5");
    }

    @Test
    void rejectsClassForSchoolOutsideTenantWith404() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> service().createClass(tenantId, new CreateClassRequest(schoolId, "Grade 5")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(classRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateClassNameWith409() {
        when(schoolRepository.existsByIdAndTenantId(schoolId, tenantId)).thenReturn(true);
        when(classRepository.existsByTenantIdAndSchoolIdAndName(tenantId, schoolId, "Grade 5")).thenReturn(true);

        assertThatThrownBy(() -> service().createClass(tenantId, new CreateClassRequest(schoolId, "Grade 5")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(classRepository, never()).save(any());
    }

    @Test
    void createsSectionUnderAnOwnedClass() {
        UUID classId = UUID.randomUUID();
        when(classRepository.findByIdAndTenantId(classId, tenantId)).thenReturn(Optional.of(schoolClass(classId, "Grade 5")));
        when(sectionRepository.existsByTenantIdAndClassIdAndName(tenantId, classId, "A")).thenReturn(false);
        when(sectionRepository.save(any(Section.class))).thenAnswer(inv -> inv.getArgument(0));

        Section created = service().createSection(tenantId, classId, new CreateSectionRequest(" A "));

        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getClassId()).isEqualTo(classId);
        assertThat(created.getName()).isEqualTo("A");
    }

    @Test
    void rejectsSectionUnderAnotherTenantsClassWith404() {
        UUID classId = UUID.randomUUID();
        when(classRepository.findByIdAndTenantId(classId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createSection(tenantId, classId, new CreateSectionRequest("A")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(sectionRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateSectionNameWith409() {
        UUID classId = UUID.randomUUID();
        when(classRepository.findByIdAndTenantId(classId, tenantId)).thenReturn(Optional.of(schoolClass(classId, "Grade 5")));
        when(sectionRepository.existsByTenantIdAndClassIdAndName(tenantId, classId, "A")).thenReturn(true);

        assertThatThrownBy(() -> service().createSection(tenantId, classId, new CreateSectionRequest("A")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listWithSectionsNestsSectionsUnderTheirClass() {
        UUID class5 = UUID.randomUUID();
        UUID class6 = UUID.randomUUID();
        when(classRepository.findByTenantIdOrderByName(tenantId))
                .thenReturn(List.of(schoolClass(class5, "Grade 5"), schoolClass(class6, "Grade 6")));
        when(sectionRepository.findByTenantIdOrderByName(tenantId)).thenReturn(List.of(
                section(UUID.randomUUID(), class5, "A"),
                section(UUID.randomUUID(), class5, "B")));

        List<ClassResponse> result = service().listWithSections(tenantId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).sections()).extracting(AcademicsDtos.SectionResponse::name)
                .containsExactly("A", "B");
        assertThat(result.get(1).sections()).isEmpty();
    }
}
