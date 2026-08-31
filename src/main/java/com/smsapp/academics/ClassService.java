package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.ClassResponse;
import com.smsapp.academics.AcademicsDtos.CreateClassRequest;
import com.smsapp.academics.AcademicsDtos.CreateSectionRequest;
import com.smsapp.academics.AcademicsDtos.SectionResponse;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ClassService {

    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final SchoolRepository schoolRepository;

    public ClassService(ClassRepository classRepository, SectionRepository sectionRepository,
                        SchoolRepository schoolRepository) {
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.schoolRepository = schoolRepository;
    }

    /**
     * @throws ApiException 404 if the school is not in the caller's tenant, 409 if a
     *         class with that name already exists for the school.
     */
    @Transactional
    public SchoolClass createClass(UUID tenantId, CreateClassRequest request) {
        String name = request.name().trim();
        if (!schoolRepository.existsByIdAndTenantId(request.schoolId(), tenantId)) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        if (classRepository.existsByTenantIdAndSchoolIdAndName(tenantId, request.schoolId(), name)) {
            throw new ApiException("A class named '" + name + "' already exists for this school", HttpStatus.CONFLICT);
        }
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.setTenantId(tenantId);
        schoolClass.setSchoolId(request.schoolId());
        schoolClass.setName(name);
        return classRepository.save(schoolClass);
    }

    /**
     * @throws ApiException 404 if the class is not in the caller's tenant, 409 if a
     *         section with that name already exists under the class.
     */
    @Transactional
    public Section createSection(UUID tenantId, UUID classId, CreateSectionRequest request) {
        String name = request.name().trim();
        if (classRepository.findByIdAndTenantId(classId, tenantId).isEmpty()) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        if (sectionRepository.existsByTenantIdAndClassIdAndName(tenantId, classId, name)) {
            throw new ApiException("A section named '" + name + "' already exists in this class", HttpStatus.CONFLICT);
        }
        Section section = new Section();
        section.setTenantId(tenantId);
        section.setClassId(classId);
        section.setName(name);
        return sectionRepository.save(section);
    }

    /** Lists the tenant's classes with their sections nested. */
    @Transactional(readOnly = true)
    public List<ClassResponse> listWithSections(UUID tenantId) {
        Map<UUID, List<SectionResponse>> sectionsByClass = sectionRepository.findByTenantIdOrderByName(tenantId).stream()
                .map(SectionResponse::from)
                .collect(Collectors.groupingBy(SectionResponse::classId));

        return classRepository.findByTenantIdOrderByName(tenantId).stream()
                .map(c -> new ClassResponse(c.getId(), c.getSchoolId(), c.getName(),
                        sectionsByClass.getOrDefault(c.getId(), List.of())))
                .toList();
    }
}
