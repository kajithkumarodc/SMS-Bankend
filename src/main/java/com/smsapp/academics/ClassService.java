package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.ClassResponse;
import com.smsapp.academics.AcademicsDtos.CreateClassRequest;
import com.smsapp.academics.AcademicsDtos.CreateSectionRequest;
import com.smsapp.academics.AcademicsDtos.SectionResponse;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
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
    private final AuditService auditService;

    public ClassService(ClassRepository classRepository, SectionRepository sectionRepository,
                        SchoolRepository schoolRepository, AuditService auditService) {
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.schoolRepository = schoolRepository;
        this.auditService = auditService;
    }

    /**
     * @throws ApiException 404 if the school does not exist, 409 if a class with
     *         that name already exists for the school.
     */
    @Transactional
    public SchoolClass createClass(CreateClassRequest request) {
        String name = request.name().trim();
        if (!schoolRepository.existsById(request.schoolId())) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        if (classRepository.existsBySchoolIdAndName(request.schoolId(), name)) {
            throw new ApiException("A class named '" + name + "' already exists for this school", HttpStatus.CONFLICT);
        }
        SchoolClass schoolClass = new SchoolClass();
        schoolClass.setSchoolId(request.schoolId());
        schoolClass.setName(name);
        SchoolClass saved = classRepository.save(schoolClass);

        auditService.log(AuditActions.CLASS_CREATED, AuditActions.CLASS, saved.getId(),
                Map.of("name", saved.getName(), "schoolId", saved.getSchoolId().toString()));
        return saved;
    }

    /**
     * @throws ApiException 404 if the class does not exist, 409 if a section with
     *         that name already exists under the class.
     */
    @Transactional
    public Section createSection(UUID classId, CreateSectionRequest request) {
        String name = request.name().trim();
        if (classRepository.findById(classId).isEmpty()) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        if (sectionRepository.existsByClassIdAndName(classId, name)) {
            throw new ApiException("A section named '" + name + "' already exists in this class", HttpStatus.CONFLICT);
        }
        Section section = new Section();
        section.setClassId(classId);
        section.setName(name);
        Section saved = sectionRepository.save(section);

        auditService.log(AuditActions.SECTION_CREATED, AuditActions.SECTION, saved.getId(),
                Map.of("name", saved.getName(), "classId", saved.getClassId().toString()));
        return saved;
    }

    /** Lists all classes with their sections nested. */
    @Transactional(readOnly = true)
    public List<ClassResponse> listWithSections() {
        Map<UUID, List<SectionResponse>> sectionsByClass = sectionRepository.findAllByOrderByName().stream()
                .map(SectionResponse::from)
                .collect(Collectors.groupingBy(SectionResponse::classId));

        return classRepository.findAllByOrderByName().stream()
                .map(c -> new ClassResponse(c.getId(), c.getSchoolId(), c.getName(),
                        sectionsByClass.getOrDefault(c.getId(), List.of())))
                .toList();
    }
}
