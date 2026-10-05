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

        // Until real sections are added, students join the class through its hidden default section.
        Section defaultSection = new Section();
        defaultSection.setClassId(saved.getId());
        defaultSection.setName(DEFAULT_SECTION_NAME);
        defaultSection.setDefaultSection(true);
        sectionRepository.save(defaultSection);

        auditService.log(AuditActions.CLASS_CREATED, AuditActions.CLASS, saved.getId(),
                Map.of("name", saved.getName(), "schoolId", saved.getSchoolId().toString()));
        return saved;
    }

    /** Stored name of a class's hidden default section (V39); the UI shows the class name instead. */
    static final String DEFAULT_SECTION_NAME = "No section";

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
        Section defaultSection = sectionRepository.findByClassIdAndDefaultSectionTrue(classId).orElse(null);
        if (sectionRepository.existsByClassIdAndName(classId, name)
                && (defaultSection == null || !defaultSection.getName().equals(name))) {
            throw new ApiException("A section named '" + name + "' already exists in this class", HttpStatus.CONFLICT);
        }
        // The class's first real section takes over its hidden default section in place, so students already
        // in the class (and their academic history) end up in this section instead of being left behind.
        Section section = defaultSection != null ? defaultSection : new Section();
        section.setClassId(classId);
        section.setName(name);
        section.setDefaultSection(false);
        Section saved = sectionRepository.save(section);

        auditService.log(AuditActions.SECTION_CREATED, AuditActions.SECTION, saved.getId(),
                Map.of("name", saved.getName(), "classId", saved.getClassId().toString(),
                        "fromWholeClass", defaultSection != null));
        return saved;
    }

    /**
     * Renames a section. The class and the students in the section are untouched.
     *
     * @throws ApiException 404 if the section isn't in that class, 409 if the class already has a section with
     *         that name.
     */
    @Transactional
    public Section renameSection(UUID classId, UUID sectionId, CreateSectionRequest request) {
        Section section = requireSection(classId, sectionId);
        String name = request.name().trim();
        if (!section.getName().equals(name) && sectionRepository.existsByClassIdAndName(classId, name)) {
            throw new ApiException("A section named '" + name + "' already exists in this class", HttpStatus.CONFLICT);
        }
        String previous = section.getName();
        section.setName(name);
        Section saved = sectionRepository.save(section);
        auditService.log(AuditActions.SECTION_UPDATED, AuditActions.SECTION, sectionId,
                Map.of("classId", classId.toString(), "from", previous, "to", name));
        return saved;
    }

    /**
     * Deletes a section. Only an empty one: students must be moved to another section first, so nobody is left
     * without a section. The class itself is never affected.
     *
     * @throws ApiException 404 if the section isn't in that class, 409 if students are still in it.
     */
    @Transactional
    public void deleteSection(UUID classId, UUID sectionId) {
        Section section = requireSection(classId, sectionId);
        long students = sectionRepository.countStudentsInSection(sectionId);
        if (students > 0) {
            throw new ApiException("Section " + section.getName() + " still has " + students + " student"
                    + (students == 1 ? "" : "s") + " -- move them to another section first", HttpStatus.CONFLICT);
        }
        if (sectionRepository.countTimetablePeriods(sectionId) > 0) {
            throw new ApiException("Section " + section.getName() + " has timetable periods -- clear its timetable first", HttpStatus.CONFLICT);
        }
        if (sectionRepository.countSubjectGroups(sectionId) > 0) {
            throw new ApiException("Section " + section.getName() + " is in a subject group -- remove it from the group first", HttpStatus.CONFLICT);
        }
        String name = section.getName();
        if (sectionRepository.countByClassIdAndDefaultSectionFalse(classId) == 1) {
            // Last real section: it becomes the class's hidden default section again, so the class can
            // still take students without any section.
            section.setName(DEFAULT_SECTION_NAME);
            section.setDefaultSection(true);
            sectionRepository.save(section);
        } else {
            sectionRepository.delete(section);
        }
        auditService.log(AuditActions.SECTION_DELETED, AuditActions.SECTION, sectionId,
                Map.of("classId", classId.toString(), "name", name));
    }

    /** A real (named) section of the class -- the hidden default section can't be renamed or deleted. */
    private Section requireSection(UUID classId, UUID sectionId) {
        return sectionRepository.findByIdAndClassId(sectionId, classId)
                .filter(section -> !section.isDefaultSection())
                .orElseThrow(() -> new ApiException("Section not found", HttpStatus.NOT_FOUND));
    }

    /** Lists all classes, in school order, with their sections nested. */
    @Transactional(readOnly = true)
    public List<ClassResponse> listWithSections() {
        Map<UUID, List<SectionResponse>> sectionsByClass = sectionRepository.findAllByOrderByName().stream()
                .map(SectionResponse::from)
                .collect(Collectors.groupingBy(SectionResponse::classId));

        return classRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(c -> new ClassResponse(c.getId(), c.getSchoolId(), c.getName(),
                        sectionsByClass.getOrDefault(c.getId(), List.of())))
                .toList();
    }
}
