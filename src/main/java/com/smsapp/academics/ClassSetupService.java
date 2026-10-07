package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.ClassResponse;
import com.smsapp.academics.AcademicsDtos.CreateClassRequest;
import com.smsapp.academics.AcademicsDtos.CreateSectionRequest;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.school.School;
import com.smsapp.school.SchoolRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Academics > Class and Sections. The Sections list is the master list of section names; a class is created, edited
 * and deleted together with the sections it picks from that list. The per-section rules of {@link ClassService}
 * apply: a section with students, timetable periods or a subject group can't be taken off a class.
 */
@Service
public class ClassSetupService {

    private final ClassService classService;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final SectionNameRepository sectionNameRepository;
    private final ClassSubjectRepository classSubjectRepository;
    private final SchoolRepository schoolRepository;
    private final AuditService auditService;

    public ClassSetupService(ClassService classService, ClassRepository classRepository, SectionRepository sectionRepository,
                             SectionNameRepository sectionNameRepository, ClassSubjectRepository classSubjectRepository,
                             SchoolRepository schoolRepository, AuditService auditService) {
        this.classService = classService;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.sectionNameRepository = sectionNameRepository;
        this.classSubjectRepository = classSubjectRepository;
        this.schoolRepository = schoolRepository;
        this.auditService = auditService;
    }

    // --- Sections list ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SectionName> sectionNames() {
        return sectionNameRepository.findAllByOrderByName();
    }

    /** @throws ApiException 409 if the name is already in the list. */
    @Transactional
    public SectionName createSectionName(String name) {
        String trimmed = name.trim();
        if (sectionNameRepository.existsByNameIgnoreCase(trimmed)) {
            throw nameTaken(trimmed);
        }
        SectionName entry = new SectionName();
        entry.setName(trimmed);
        SectionName saved = saveName(entry, trimmed);
        auditService.log(AuditActions.SECTION_NAME_CREATED, AuditActions.SECTION_NAME, saved.getId(), Map.of("name", trimmed));
        return saved;
    }

    /** Renames an entry and the section of every class that uses it. @throws ApiException 404 if none, 409 if the new name clashes. */
    @Transactional
    public SectionName renameSectionName(UUID id, String name) {
        SectionName entry = requireName(id);
        String trimmed = name.trim();
        if (sectionNameRepository.existsByNameIgnoreCaseAndIdNot(trimmed, id)) {
            throw nameTaken(trimmed);
        }
        String previous = entry.getName();
        if (!previous.equals(trimmed)) {
            // A class that already has a section called like the new name would end up with two.
            for (SchoolClass schoolClass : classRepository.findAll()) {
                if (sectionRepository.existsByClassIdAndName(schoolClass.getId(), trimmed)
                        && sectionRepository.existsByClassIdAndName(schoolClass.getId(), previous)) {
                    throw new ApiException(schoolClass.getName() + " already has a section named '" + trimmed + "'", HttpStatus.CONFLICT);
                }
            }
            sectionRepository.renameRealSections(previous, trimmed);
        }
        entry.setName(trimmed);
        SectionName saved = saveName(entry, trimmed);
        auditService.log(AuditActions.SECTION_NAME_UPDATED, AuditActions.SECTION_NAME, id, Map.of("from", previous, "to", trimmed));
        return saved;
    }

    /** @throws ApiException 404 if none, 409 if a class uses the section. */
    @Transactional
    public void deleteSectionName(UUID id) {
        SectionName entry = requireName(id);
        if (sectionRepository.existsRealSectionNamed(entry.getName())) {
            throw new ApiException("Section '" + entry.getName() + "' is used by a class and can't be deleted", HttpStatus.CONFLICT);
        }
        sectionNameRepository.delete(entry);
        auditService.log(AuditActions.SECTION_NAME_DELETED, AuditActions.SECTION_NAME, id, Map.of("name", entry.getName()));
    }

    // --- Classes --------------------------------------------------------------------------------

    /**
     * Creates a class with the chosen sections.
     *
     * @throws ApiException 404 if the school hasn't been set up or a section isn't in the Sections list, 409 if the class exists.
     */
    @Transactional
    public ClassResponse createClass(String name, List<String> sectionNames) {
        School school = schoolRepository.findAllByOrderByName().stream().findFirst()
                .orElseThrow(() -> new ApiException("School not found", HttpStatus.NOT_FOUND));
        List<String> sections = canonicalSections(sectionNames);
        if (sections.isEmpty()) {
            throw new ApiException("Choose at least one section", HttpStatus.BAD_REQUEST);
        }
        SchoolClass created = classService.createClass(new CreateClassRequest(school.getId(), name));
        for (String section : sections) {
            classService.createSection(created.getId(), new CreateSectionRequest(section));
        }
        return response(created.getId());
    }

    /**
     * Renames a class and makes its sections exactly the chosen ones: new ones are added and unchecked ones removed.
     *
     * @throws ApiException 404 if the class or a section isn't found, 409 on a duplicate class name or when a removed
     *         section still has students, timetable periods or a subject group.
     */
    @Transactional
    public ClassResponse updateClass(UUID classId, String name, List<String> sectionNames) {
        SchoolClass schoolClass = classRepository.findById(classId).orElseThrow(() -> new ApiException("Class not found", HttpStatus.NOT_FOUND));
        String trimmed = name.trim();
        if (!schoolClass.getName().equals(trimmed) && classRepository.existsBySchoolIdAndName(schoolClass.getSchoolId(), trimmed)) {
            throw new ApiException("A class named '" + trimmed + "' already exists for this school", HttpStatus.CONFLICT);
        }
        List<String> wanted = canonicalSections(sectionNames);
        Set<String> wantedKeys = wanted.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<Section> current = sectionRepository.findAllByOrderByName().stream()
                .filter(s -> s.getClassId().equals(classId) && !s.isDefaultSection()).toList();
        Set<String> currentKeys = current.stream().map(s -> s.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        // Add before removing, so the class never passes through "no sections" while it still has students.
        for (String section : wanted) {
            if (!currentKeys.contains(section.toLowerCase(Locale.ROOT))) {
                classService.createSection(classId, new CreateSectionRequest(section));
            }
        }
        for (Section section : current) {
            if (!wantedKeys.contains(section.getName().toLowerCase(Locale.ROOT))) {
                classService.deleteSection(classId, section.getId());
            }
        }
        String previous = schoolClass.getName();
        schoolClass.setName(trimmed);
        classRepository.save(schoolClass);
        auditService.log(AuditActions.CLASS_UPDATED, AuditActions.CLASS, classId, Map.of("from", previous, "to", trimmed));
        return response(classId);
    }

    /** @throws ApiException 404 if no such class, 409 if it has students, subjects, timetable periods or is used elsewhere. */
    @Transactional
    public void deleteClass(UUID classId) {
        SchoolClass schoolClass = classRepository.findById(classId).orElseThrow(() -> new ApiException("Class not found", HttpStatus.NOT_FOUND));
        if (!classSubjectRepository.findSubjectsForClass(classId).isEmpty()) {
            throw inUse(schoolClass, "has subjects assigned");
        }
        List<Section> sections = sectionRepository.findAllByOrderByName().stream().filter(s -> s.getClassId().equals(classId)).toList();
        for (Section section : sections) {
            if (sectionRepository.countStudentsInSection(section.getId()) > 0) {
                throw inUse(schoolClass, "still has students");
            }
            if (sectionRepository.countTimetablePeriods(section.getId()) > 0 || sectionRepository.countSubjectGroups(section.getId()) > 0) {
                throw inUse(schoolClass, "has a timetable or subject group");
            }
        }
        try {
            sectionRepository.deleteAll(sections);
            sectionRepository.flush();
            classRepository.delete(schoolClass);
            classRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw inUse(schoolClass, "is used by other records (fees, exams or promotions)");
        }
        auditService.log(AuditActions.CLASS_DELETED, AuditActions.CLASS, classId, Map.of("name", schoolClass.getName()));
    }

    // --- Helpers --------------------------------------------------------------------------------

    private ClassResponse response(UUID classId) {
        return classService.listWithSections().stream().filter(c -> c.id().equals(classId)).findFirst()
                .orElseThrow(() -> new ApiException("Class not found", HttpStatus.NOT_FOUND));
    }

    /** The chosen names as spelled in the Sections list, without repeats. */
    private List<String> canonicalSections(List<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names == null ? List.<String>of() : names) {
            SectionName entry = sectionNameRepository.findByNameIgnoreCase(name.trim())
                    .orElseThrow(() -> new ApiException("Section '" + name.trim() + "' is not in the Sections list", HttpStatus.NOT_FOUND));
            result.add(entry.getName());
        }
        return new ArrayList<>(result);
    }

    private SectionName requireName(UUID id) {
        return sectionNameRepository.findById(id).orElseThrow(() -> new ApiException("Section not found", HttpStatus.NOT_FOUND));
    }

    private SectionName saveName(SectionName entry, String name) {
        try {
            return sectionNameRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(name);
        }
    }

    private static ApiException nameTaken(String name) {
        return new ApiException("A section named '" + name + "' already exists", HttpStatus.CONFLICT);
    }

    private static ApiException inUse(SchoolClass schoolClass, String why) {
        return new ApiException("'" + schoolClass.getName() + "' " + why + " and can't be deleted", HttpStatus.CONFLICT);
    }
}
