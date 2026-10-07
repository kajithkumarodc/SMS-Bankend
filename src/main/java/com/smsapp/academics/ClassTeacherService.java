package com.smsapp.academics;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.staff.StaffProfile;
import com.smsapp.staff.StaffProfileRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Academics > Assign Class Teacher: the teachers in charge of each section of each class. */
@Service
public class ClassTeacherService {

    /** One teacher of a section. */
    public record TeacherRef(UUID staffProfileId, String name, String staffId) {
    }

    /** A section with the teachers in charge of it; {@code sectionName} is empty for a class without sections. */
    public record ClassTeacherRow(UUID classId, String className, UUID sectionId, String sectionName, boolean wholeClass,
                                  List<TeacherRef> teachers) {
    }

    private final ClassTeacherRepository classTeacherRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final StaffProfileRepository staffRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public ClassTeacherService(ClassTeacherRepository classTeacherRepository, ClassRepository classRepository,
                               SectionRepository sectionRepository, StaffProfileRepository staffRepository,
                               UserRepository userRepository, AuditService auditService) {
        this.classTeacherRepository = classTeacherRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.staffRepository = staffRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** Every section that has a class teacher, in school class order and then by section name. */
    @Transactional(readOnly = true)
    public List<ClassTeacherRow> list() {
        List<ClassTeacher> all = classTeacherRepository.findAll();
        Map<UUID, Section> sections = sectionRepository.findAll().stream().collect(Collectors.toMap(Section::getId, Function.identity()));
        Map<UUID, Integer> classOrder = new java.util.HashMap<>();
        Map<UUID, SchoolClass> classes = new java.util.LinkedHashMap<>();
        int index = 0;
        for (SchoolClass c : classRepository.findAllByOrderBySortOrderAscNameAsc()) {
            classes.put(c.getId(), c);
            classOrder.put(c.getId(), index++);
        }
        Map<UUID, StaffProfile> staff = staffRepository.findAll().stream().collect(Collectors.toMap(StaffProfile::getId, Function.identity()));
        Map<UUID, String> names = userRepository.findAllById(staff.values().stream().map(StaffProfile::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getFullName));
        Map<UUID, List<ClassTeacher>> bySection = all.stream().collect(Collectors.groupingBy(ClassTeacher::getSectionId));
        return bySection.entrySet().stream()
                .map(e -> {
                    Section section = sections.get(e.getKey());
                    if (section == null) {
                        return null;
                    }
                    SchoolClass schoolClass = classes.get(section.getClassId());
                    List<TeacherRef> teachers = e.getValue().stream().map(ct -> staff.get(ct.getStaffProfileId())).filter(s -> s != null)
                            .sorted(Comparator.comparing(StaffProfile::getEmployeeCode, Comparator.nullsLast(ClassTeacherService::compareCodes)))
                            .map(s -> new TeacherRef(s.getId(), names.get(s.getUserId()), s.getEmployeeCode())).toList();
                    return new ClassTeacherRow(section.getClassId(), schoolClass == null ? null : schoolClass.getName(), section.getId(),
                            section.isDefaultSection() ? "" : section.getName(), section.isDefaultSection(), teachers);
                })
                .filter(r -> r != null)
                .sorted(Comparator.<ClassTeacherRow>comparingInt(r -> classOrder.getOrDefault(r.classId(), Integer.MAX_VALUE))
                        .thenComparing(ClassTeacherRow::sectionName))
                .toList();
    }

    /**
     * Makes the given teachers the class teachers of a section, replacing any it had.
     *
     * @throws ApiException 404 if the section or a staff member doesn't exist, 400 if no teacher is given or one is disabled.
     */
    @Transactional
    public ClassTeacherRow assign(UUID sectionId, List<UUID> staffProfileIds) {
        Section section = sectionRepository.findById(sectionId).orElseThrow(() -> new ApiException("Section not found", HttpStatus.NOT_FOUND));
        Set<UUID> wanted = new LinkedHashSet<>(staffProfileIds);
        if (wanted.isEmpty()) {
            throw new ApiException("Choose at least one class teacher", HttpStatus.BAD_REQUEST);
        }
        for (UUID id : wanted) {
            StaffProfile profile = staffRepository.findById(id).orElseThrow(() -> new ApiException("Staff member not found", HttpStatus.NOT_FOUND));
            if (!"ACTIVE".equals(profile.getStatus())) {
                throw new ApiException("A chosen teacher is disabled", HttpStatus.BAD_REQUEST);
            }
        }
        classTeacherRepository.deleteBySectionId(sectionId);
        classTeacherRepository.flush();
        for (UUID id : wanted) {
            ClassTeacher assignment = new ClassTeacher();
            assignment.setSectionId(sectionId);
            assignment.setStaffProfileId(id);
            classTeacherRepository.save(assignment);
        }
        auditService.log(AuditActions.CLASS_TEACHER_ASSIGNED, AuditActions.CLASS_TEACHER, sectionId,
                Map.of("classId", section.getClassId().toString(), "teachers", wanted.size()));
        return list().stream().filter(r -> r.sectionId().equals(sectionId)).findFirst()
                .orElseThrow(() -> new ApiException("Section not found", HttpStatus.NOT_FOUND));
    }

    /** Removes every class teacher of a section. @throws ApiException 404 if the section has none. */
    @Transactional
    public void remove(UUID sectionId) {
        if (!classTeacherRepository.existsBySectionId(sectionId)) {
            throw new ApiException("This section has no class teacher", HttpStatus.NOT_FOUND);
        }
        classTeacherRepository.deleteBySectionId(sectionId);
        auditService.log(AuditActions.CLASS_TEACHER_REMOVED, AuditActions.CLASS_TEACHER, sectionId, Map.of());
    }

    /** Staff IDs in natural order: 9002 before 90006, E-2 before E-10. */
    private static int compareCodes(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            if (Character.isDigit(a.charAt(i)) && Character.isDigit(b.charAt(j))) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                int cmp = new java.math.BigInteger(a.substring(si, i)).compareTo(new java.math.BigInteger(b.substring(sj, j)));
                if (cmp != 0) return cmp;
            } else {
                int cmp = Character.compare(Character.toLowerCase(a.charAt(i)), Character.toLowerCase(b.charAt(j)));
                if (cmp != 0) return cmp;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
