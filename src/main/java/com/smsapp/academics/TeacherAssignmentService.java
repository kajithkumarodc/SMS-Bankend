package com.smsapp.academics;

import com.smsapp.common.ApiException;
import com.smsapp.student.Student;
import com.smsapp.student.StudentDtos.StudentResponse;
import com.smsapp.student.StudentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Teacher Panel (Phase 4 part C). The first and only teacher-to-class/section/subject assignment
 * model in the codebase -- extends the existing {@code class_subjects} join table with a nullable
 * {@code teacher_id} (V25) rather than introducing a second one, per
 * "Academic Session -> Class -> Section -> Subject -> Teacher -> Students".
 *
 * <p>Every read here is scoped to the calling teacher's own assignments; an id outside that scope
 * is reported as 404, never 403, matching the ownership pattern already used for parent/portal
 * access in {@code PortalService}.
 */
@Service
public class TeacherAssignmentService {

    private final ClassSubjectRepository classSubjectRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final SubjectRepository subjectRepository;
    private final StudentRepository studentRepository;

    public TeacherAssignmentService(ClassSubjectRepository classSubjectRepository, ClassRepository classRepository,
                                    SectionRepository sectionRepository, SubjectRepository subjectRepository,
                                    StudentRepository studentRepository) {
        this.classSubjectRepository = classSubjectRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.subjectRepository = subjectRepository;
        this.studentRepository = studentRepository;
    }

    /** Publicly-accessible section shape (unlike package-private {@code AcademicsDtos.SectionResponse}), so this can cross into {@code com.smsapp.dashboard}. */
    public record SectionInfo(UUID id, String name) {
    }

    public record AssignmentView(UUID classId, String className, UUID subjectId, String subjectName,
                                 List<SectionInfo> sections) {
    }

    /**
     * Assigns (or, with {@code teacherId == null}, unassigns) a teacher to teach a subject for a class.
     * The subject must already be linked to the class via the existing
     * {@link SubjectService#assignToClass} flow -- this only records who teaches it.
     *
     * @throws ApiException 404 if the class/subject are not linked yet.
     */
    @Transactional
    public void assignTeacher(UUID classId, UUID subjectId, UUID teacherId) {
        ClassSubject link = classSubjectRepository.findByClassIdAndSubjectId(classId, subjectId)
                .orElseThrow(() -> new ApiException(
                        "That subject is not assigned to this class yet", HttpStatus.NOT_FOUND));
        link.setTeacherId(teacherId);
        classSubjectRepository.save(link);
    }

    /** Every class/section/subject combination this teacher is assigned to teach. */
    @Transactional(readOnly = true)
    public List<AssignmentView> myAssignments(UUID teacherId) {
        List<ClassSubject> links = classSubjectRepository.findByTeacherId(teacherId);
        if (links.isEmpty()) {
            return List.of();
        }

        Set<UUID> classIds = links.stream().map(ClassSubject::getClassId).collect(Collectors.toSet());
        Set<UUID> subjectIds = links.stream().map(ClassSubject::getSubjectId).collect(Collectors.toSet());

        Map<UUID, String> classNames = classRepository.findByIdIn(classIds).stream()
                .collect(Collectors.toMap(SchoolClass::getId, SchoolClass::getName));
        Map<UUID, String> subjectNames = subjectRepository.findAllById(subjectIds).stream()
                .collect(Collectors.toMap(Subject::getId, Subject::getName));
        Map<UUID, List<SectionInfo>> sectionsByClass = new HashMap<>();
        sectionRepository.findByClassIdIn(classIds).forEach(section ->
                sectionsByClass.computeIfAbsent(section.getClassId(), k -> new java.util.ArrayList<>())
                        .add(new SectionInfo(section.getId(), section.getName())));

        return links.stream()
                .map(link -> new AssignmentView(
                        link.getClassId(), classNames.get(link.getClassId()),
                        link.getSubjectId(), subjectNames.get(link.getSubjectId()),
                        sectionsByClass.getOrDefault(link.getClassId(), List.of())))
                .toList();
    }

    /** All sections across every class this teacher is assigned to (for the student-roster query). */
    @Transactional(readOnly = true)
    public Set<UUID> assignedSectionIds(UUID teacherId) {
        Set<UUID> classIds = classSubjectRepository.findByTeacherId(teacherId).stream()
                .map(ClassSubject::getClassId)
                .collect(Collectors.toSet());
        if (classIds.isEmpty()) {
            return Set.of();
        }
        return sectionRepository.findByClassIdIn(classIds).stream()
                .map(Section::getId)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public long studentCount(UUID teacherId) {
        Set<UUID> sectionIds = assignedSectionIds(teacherId);
        return sectionIds.isEmpty() ? 0 : studentRepository.countBySectionIdIn(sectionIds);
    }

    @Transactional(readOnly = true)
    public Page<StudentResponse> myStudents(UUID teacherId, Pageable pageable) {
        Set<UUID> sectionIds = assignedSectionIds(teacherId);
        if (sectionIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return studentRepository.findBySectionIdInOrderByFullName(sectionIds, pageable).map(StudentResponse::from);
    }

    /** @throws ApiException 404 if this student is not in any of the teacher's assigned sections. */
    @Transactional(readOnly = true)
    public StudentResponse myStudent(UUID teacherId, UUID studentId) {
        Set<UUID> sectionIds = assignedSectionIds(teacherId);
        Student student = (sectionIds.isEmpty() ? java.util.Optional.<Student>empty()
                : studentRepository.findByIdAndSectionIdIn(studentId, sectionIds))
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
        return StudentResponse.from(student);
    }
}
