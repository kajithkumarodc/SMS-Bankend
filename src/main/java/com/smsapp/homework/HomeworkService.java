package com.smsapp.homework;

import com.smsapp.academics.ClassRepository;
import com.smsapp.academics.Section;
import com.smsapp.academics.SectionRepository;
import com.smsapp.academics.SubjectRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.homework.HomeworkDtos.CreateHomeworkRequest;
import com.smsapp.homework.HomeworkDtos.RecordSubmissionRequest;
import com.smsapp.homework.HomeworkDtos.StudentHomeworkResponse;
import com.smsapp.homework.HomeworkDtos.SubmissionRowResponse;
import com.smsapp.homework.HomeworkDtos.UpdateHomeworkRequest;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Homework: a teacher sets work for one section in one subject, then records who
 * handed it in. Reads for staff are unscoped (a teacher sees any section they ask
 * for, same as the exam gradebook); a student or parent reads their own through
 * {@code /api/v1/me/...}, which resolves the student first.
 */
@Service
public class HomeworkService {

    private final HomeworkRepository homeworkRepository;
    private final HomeworkSubmissionRepository submissionRepository;
    private final ClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final SubjectRepository subjectRepository;
    private final StudentRepository studentRepository;
    private final AuditService auditService;
    private final SchoolClock clock;

    public HomeworkService(HomeworkRepository homeworkRepository,
                           HomeworkSubmissionRepository submissionRepository,
                           ClassRepository classRepository, SectionRepository sectionRepository,
                           SubjectRepository subjectRepository, StudentRepository studentRepository,
                           AuditService auditService, SchoolClock clock) {
        this.homeworkRepository = homeworkRepository;
        this.submissionRepository = submissionRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.subjectRepository = subjectRepository;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** {@code created} is true when the row was newly inserted, false when an existing one was updated. */
    public record SubmissionResult(HomeworkSubmission submission, boolean created) {
    }

    /**
     * @throws ApiException 404 if the class, section or subject does not exist; 400 if the
     *         section is not part of the class, or the due date precedes the assigned date.
     */
    @Transactional
    public Homework create(UUID createdByUserId, CreateHomeworkRequest request) {
        requireClass(request.classId());
        requireSubject(request.subjectId());
        Section section = requireSection(request.sectionId());
        if (!section.getClassId().equals(request.classId())) {
            throw new ApiException("Section does not belong to that class", HttpStatus.BAD_REQUEST);
        }

        // Defaulting here rather than in the DTO keeps "today" the school's today.
        LocalDate assignedDate = request.assignedDate() != null ? request.assignedDate() : clock.today();
        requireDueOnOrAfterAssigned(request.dueDate(), assignedDate);

        Homework homework = new Homework();
        homework.setClassId(request.classId());
        homework.setSectionId(request.sectionId());
        homework.setSubjectId(request.subjectId());
        homework.setTitle(request.title().trim());
        homework.setDescription(trimToNull(request.description()));
        homework.setAssignedDate(assignedDate);
        homework.setDueDate(request.dueDate());
        homework.setCreatedByUserId(createdByUserId);
        Homework saved = homeworkRepository.save(homework);

        auditService.log(AuditActions.HOMEWORK_CREATED, AuditActions.HOMEWORK, saved.getId(),
                Map.of("title", saved.getTitle(), "sectionId", saved.getSectionId().toString(),
                        "dueDate", saved.getDueDate().toString()));
        return saved;
    }

    /**
     * A section's homework, newest first, optionally narrowed to one subject.
     *
     * @throws ApiException 404 if the section does not exist (no existence leak).
     */
    @Transactional(readOnly = true)
    public Page<Homework> listForSection(UUID sectionId, UUID subjectId, Pageable pageable) {
        requireSection(sectionId);
        return subjectId == null
                ? homeworkRepository.findBySectionIdOrderByAssignedDateDescCreatedAtDesc(sectionId, pageable)
                : homeworkRepository.findBySectionIdAndSubjectIdOrderByAssignedDateDescCreatedAtDesc(
                        sectionId, subjectId, pageable);
    }

    /**
     * @throws ApiException 404 if no such homework.
     */
    @Transactional(readOnly = true)
    public Homework get(UUID id) {
        return requireHomework(id);
    }

    /**
     * @throws ApiException 404 if no such homework; 400 if the new due date precedes
     *         the (unchanged) assigned date.
     */
    @Transactional
    public Homework update(UUID id, UpdateHomeworkRequest request) {
        Homework homework = requireHomework(id);
        requireDueOnOrAfterAssigned(request.dueDate(), homework.getAssignedDate());

        homework.setTitle(request.title().trim());
        homework.setDescription(trimToNull(request.description()));
        homework.setDueDate(request.dueDate());
        Homework saved = homeworkRepository.save(homework);

        auditService.log(AuditActions.HOMEWORK_UPDATED, AuditActions.HOMEWORK, saved.getId(),
                Map.of("title", saved.getTitle(), "dueDate", saved.getDueDate().toString()));
        return saved;
    }

    /**
     * Deletes the homework and every submission recorded against it (the FK cascades).
     *
     * @throws ApiException 404 if no such homework.
     */
    @Transactional
    public void delete(UUID id) {
        Homework homework = requireHomework(id);
        submissionRepository.deleteByHomeworkId(id);
        homeworkRepository.delete(homework);

        auditService.log(AuditActions.HOMEWORK_DELETED, AuditActions.HOMEWORK, id,
                Map.of("title", homework.getTitle()));
    }

    /**
     * The teacher's submission sheet: every student on the section roster, joined to
     * whatever has been recorded. A student with no row shows as PENDING rather than
     * being left out, so the sheet is always the whole class.
     *
     * @throws ApiException 404 if no such homework.
     */
    @Transactional(readOnly = true)
    public List<SubmissionRowResponse> submissionSheet(UUID homeworkId) {
        Homework homework = requireHomework(homeworkId);
        Map<UUID, HomeworkSubmission> recorded = submissionRepository.findByHomeworkId(homeworkId).stream()
                .collect(Collectors.toMap(HomeworkSubmission::getStudentId, Function.identity()));

        return studentRepository.findBySectionIdInOrderByFullName(List.of(homework.getSectionId()), Pageable.unpaged())
                .stream()
                .map(student -> {
                    HomeworkSubmission submission = recorded.get(student.getId());
                    return new SubmissionRowResponse(
                            student.getId(),
                            student.getFullName(),
                            student.getRollNumber(),
                            submission != null ? submission.getStatus() : HomeworkSubmissionStatus.PENDING,
                            submission != null ? submission.getSubmittedAt() : null,
                            submission != null ? submission.getRemarks() : null,
                            submission != null ? submission.getMarkedAt() : null);
                })
                .toList();
    }

    /**
     * Records (or corrects) one student's state. Re-recording the same student updates
     * the row in place rather than failing, same convention as attendance marking.
     *
     * @throws ApiException 404 if the homework or the student does not exist; 400 if the
     *         status is unknown or the student is not on that homework's section roster.
     */
    @Transactional
    public SubmissionResult recordSubmission(UUID markedByUserId, UUID homeworkId, UUID studentId,
                                             RecordSubmissionRequest request) {
        Homework homework = requireHomework(homeworkId);
        String status = HomeworkSubmissionStatus.normalizeOrNull(request.status());
        if (status == null) {
            throw new ApiException("Status must be PENDING, SUBMITTED, LATE or NOT_SUBMITTED",
                    HttpStatus.BAD_REQUEST);
        }

        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
        if (!homework.getSectionId().equals(student.getSectionId())) {
            throw new ApiException("Student is not in the section this homework was set for",
                    HttpStatus.BAD_REQUEST);
        }

        HomeworkSubmission existing = submissionRepository
                .findByHomeworkIdAndStudentId(homeworkId, studentId)
                .orElse(null);
        boolean created = existing == null;
        HomeworkSubmission submission = existing;
        if (created) {
            submission = new HomeworkSubmission();
            submission.setHomeworkId(homeworkId);
            submission.setStudentId(studentId);
        }

        submission.setStatus(status);
        // Only the handed-in statuses carry a submission time; moving a student back to
        // PENDING or NOT_SUBMITTED clears it rather than leaving a stale timestamp.
        submission.setSubmittedAt(HomeworkSubmissionStatus.isHandedIn(status) ? clock.now() : null);
        submission.setRemarks(trimToNull(request.remarks()));
        submission.setMarkedByUserId(markedByUserId);
        submission.setMarkedAt(clock.now());
        HomeworkSubmission saved = submissionRepository.save(submission);

        auditService.log(AuditActions.HOMEWORK_SUBMISSION_RECORDED, AuditActions.HOMEWORK_SUBMISSION, saved.getId(),
                Map.of("homeworkId", homeworkId.toString(), "studentId", studentId.toString(), "status", status));
        return new SubmissionResult(saved, created);
    }

    /**
     * The student/parent portal list: everything set for the student's current section,
     * newest first, each row carrying that student's own state.
     *
     * @param from optional lower bound on the assigned date, so a parent opening the app
     *             is not handed the whole academic year.
     */
    @Transactional(readOnly = true)
    public List<StudentHomeworkResponse> forStudent(Student student, LocalDate from) {
        if (student.getSectionId() == null) {
            return List.of();
        }
        List<UUID> sections = List.of(student.getSectionId());
        List<Homework> homework = from == null
                ? homeworkRepository.findBySectionIdInOrderByAssignedDateDescCreatedAtDesc(sections)
                : homeworkRepository
                        .findBySectionIdInAndAssignedDateGreaterThanEqualOrderByAssignedDateDescCreatedAtDesc(
                                sections, from);
        if (homework.isEmpty()) {
            return List.of();
        }

        Map<UUID, HomeworkSubmission> own = submissionRepository
                .findByStudentIdAndHomeworkIdIn(student.getId(), homework.stream().map(Homework::getId).toList())
                .stream()
                .collect(Collectors.toMap(HomeworkSubmission::getHomeworkId, Function.identity()));

        return homework.stream()
                .map(item -> {
                    HomeworkSubmission submission = own.get(item.getId());
                    return new StudentHomeworkResponse(
                            item.getId(),
                            item.getSubjectId(),
                            item.getTitle(),
                            item.getDescription(),
                            item.getAssignedDate(),
                            item.getDueDate(),
                            submission != null ? submission.getStatus() : HomeworkSubmissionStatus.PENDING,
                            submission != null ? submission.getSubmittedAt() : null,
                            submission != null ? submission.getRemarks() : null);
                })
                .toList();
    }

    private Homework requireHomework(UUID id) {
        return homeworkRepository.findById(id)
                .orElseThrow(() -> new ApiException("Homework not found", HttpStatus.NOT_FOUND));
    }

    private Section requireSection(UUID sectionId) {
        return sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ApiException("Section not found", HttpStatus.NOT_FOUND));
    }

    private void requireClass(UUID classId) {
        if (!classRepository.existsById(classId)) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
    }

    private void requireSubject(UUID subjectId) {
        if (!subjectRepository.existsById(subjectId)) {
            throw new ApiException("Subject not found", HttpStatus.NOT_FOUND);
        }
    }

    private static void requireDueOnOrAfterAssigned(LocalDate dueDate, LocalDate assignedDate) {
        if (dueDate.isBefore(assignedDate)) {
            throw new ApiException("Due date cannot be before the assigned date", HttpStatus.BAD_REQUEST);
        }
    }

    private static String trimToNull(String value) {
        return Optional.ofNullable(value).map(String::trim).filter(text -> !text.isEmpty()).orElse(null);
    }
}
