package com.smsapp.timetable;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.SchoolClass;
import com.smsapp.academics.Section;
import com.smsapp.academics.SectionRepository;
import com.smsapp.academics.Subject;
import com.smsapp.academics.SubjectRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.timetable.TimetableDtos.SectionRef;
import com.smsapp.timetable.TimetableDtos.SubjectGroupRequest;
import com.smsapp.timetable.TimetableDtos.SubjectGroupResponse;
import com.smsapp.timetable.TimetableDtos.SubjectRef;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Academics > Subject Group: the subjects that some sections of one class study together. Names are unique ignoring
 * case. A group that has timetable periods cannot be deleted.
 */
@Service
public class SubjectGroupService {

    private final SubjectGroupRepository groupRepository;
    private final TimetableEntryRepository timetableRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final SubjectRepository subjectRepository;
    private final AuditService auditService;

    public SubjectGroupService(SubjectGroupRepository groupRepository, TimetableEntryRepository timetableRepository,
                               ClassRepository classRepository, SectionRepository sectionRepository,
                               SubjectRepository subjectRepository, AuditService auditService) {
        this.groupRepository = groupRepository;
        this.timetableRepository = timetableRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.subjectRepository = subjectRepository;
        this.auditService = auditService;
    }

    /** The groups, ordered by name; {@code sectionId} narrows to the groups that cover that section. */
    @Transactional(readOnly = true)
    public List<SubjectGroupResponse> list(UUID sectionId) {
        List<SubjectGroup> groups = sectionId == null ? groupRepository.findAllByOrderByName() : groupRepository.findBySection(sectionId);
        return toResponses(groups);
    }

    /** @throws ApiException 404 if the class, a section or a subject doesn't exist, 400 if a section is of another class, 409 on a duplicate name. */
    @Transactional
    public SubjectGroupResponse create(SubjectGroupRequest request) {
        String name = request.name().trim();
        if (groupRepository.existsByNameIgnoreCase(name)) {
            throw nameTaken(name);
        }
        SubjectGroup group = new SubjectGroup();
        apply(group, request);
        SubjectGroup saved = save(group, name);
        auditService.log(AuditActions.SUBJECT_GROUP_CREATED, AuditActions.SUBJECT_GROUP, saved.getId(), Map.of("name", name));
        return toResponses(List.of(saved)).get(0);
    }

    /**
     * Changes a group. Sections or subjects that the timetable already uses can't be taken out of it.
     *
     * @throws ApiException 404 if no such group, 409 on a duplicate name or when removing something the timetable uses.
     */
    @Transactional
    public SubjectGroupResponse update(UUID id, SubjectGroupRequest request) {
        SubjectGroup group = require(id);
        String name = request.name().trim();
        if (groupRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw nameTaken(name);
        }
        Set<UUID> newSections = new HashSet<>(request.sectionIds());
        Set<UUID> newSubjects = new HashSet<>(request.subjectIds());
        for (var entry : timetableRepository.findAll()) {
            if (!entry.getSubjectGroupId().equals(id)) {
                continue;
            }
            if (!newSections.contains(entry.getSectionId())) {
                throw new ApiException("A section of this group has timetable periods, so it can't be removed from the group", HttpStatus.CONFLICT);
            }
            if (!newSubjects.contains(entry.getSubjectId())) {
                throw new ApiException("A subject of this group is on the timetable, so it can't be removed from the group", HttpStatus.CONFLICT);
            }
        }
        apply(group, request);
        SubjectGroup saved = save(group, name);
        auditService.log(AuditActions.SUBJECT_GROUP_UPDATED, AuditActions.SUBJECT_GROUP, id, Map.of("name", name));
        return toResponses(List.of(saved)).get(0);
    }

    /** @throws ApiException 404 if no such group, 409 if it has timetable periods. */
    @Transactional
    public void delete(UUID id) {
        SubjectGroup group = require(id);
        if (timetableRepository.existsBySubjectGroupId(id)) {
            throw new ApiException("'" + group.getName() + "' has timetable periods and can't be deleted", HttpStatus.CONFLICT);
        }
        groupRepository.delete(group);
        auditService.log(AuditActions.SUBJECT_GROUP_DELETED, AuditActions.SUBJECT_GROUP, id, Map.of("name", group.getName()));
    }

    SubjectGroup require(UUID id) {
        return groupRepository.findById(id).orElseThrow(() -> new ApiException("Subject group not found", HttpStatus.NOT_FOUND));
    }

    private void apply(SubjectGroup group, SubjectGroupRequest request) {
        SchoolClass schoolClass = classRepository.findById(request.classId())
                .orElseThrow(() -> new ApiException("Class not found", HttpStatus.NOT_FOUND));
        for (UUID sectionId : request.sectionIds()) {
            Section section = sectionRepository.findById(sectionId).orElseThrow(() -> new ApiException("Section not found", HttpStatus.NOT_FOUND));
            if (!section.getClassId().equals(schoolClass.getId())) {
                throw new ApiException("A section does not belong to " + schoolClass.getName(), HttpStatus.BAD_REQUEST);
            }
        }
        for (UUID subjectId : request.subjectIds()) {
            if (!subjectRepository.existsById(subjectId)) {
                throw new ApiException("Subject not found", HttpStatus.NOT_FOUND);
            }
        }
        group.setName(request.name().trim());
        String description = request.description() == null ? null : request.description().trim();
        group.setDescription(description == null || description.isEmpty() ? null : description);
        group.getSectionIds().clear();
        group.getSectionIds().addAll(request.sectionIds());
        group.getSubjectIds().clear();
        group.getSubjectIds().addAll(request.subjectIds());
    }

    private SubjectGroup save(SubjectGroup group, String name) {
        try {
            return groupRepository.saveAndFlush(group);
        } catch (DataIntegrityViolationException ex) {
            throw nameTaken(name);
        }
    }

    private List<SubjectGroupResponse> toResponses(List<SubjectGroup> groups) {
        Map<UUID, Section> sections = sectionRepository.findAll().stream().collect(Collectors.toMap(Section::getId, Function.identity()));
        Map<UUID, Subject> subjects = subjectRepository.findAll().stream().collect(Collectors.toMap(Subject::getId, Function.identity()));
        Map<UUID, String> classNames = classRepository.findAll().stream().collect(Collectors.toMap(SchoolClass::getId, SchoolClass::getName));
        return groups.stream().map(g -> {
            List<Section> groupSections = g.getSectionIds().stream().map(sections::get).filter(s -> s != null)
                    .sorted(Comparator.comparing(Section::getName)).toList();
            UUID classId = groupSections.isEmpty() ? null : groupSections.get(0).getClassId();
            return new SubjectGroupResponse(g.getId(), g.getName(), g.getDescription(), classId, classId == null ? null : classNames.get(classId),
                    groupSections.stream().map(s -> new SectionRef(s.getId(), s.getName(), s.isDefaultSection())).toList(),
                    g.getSubjectIds().stream().map(subjects::get).filter(s -> s != null).sorted(Comparator.comparing(Subject::getName))
                            .map(s -> new SubjectRef(s.getId(), s.getName(), s.getCode())).toList());
        }).toList();
    }

    private static ApiException nameTaken(String name) {
        return new ApiException("A subject group named '" + name + "' already exists", HttpStatus.CONFLICT);
    }
}
