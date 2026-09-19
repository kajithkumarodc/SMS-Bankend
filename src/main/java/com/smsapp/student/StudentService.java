package com.smsapp.student;

import com.smsapp.academics.SectionRepository;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentDtos.UpdateStudentRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class StudentService {

    private final StudentRepository studentRepository;
    private final SchoolRepository schoolRepository;
    private final SectionRepository sectionRepository;
    private final AuditService auditService;

    public StudentService(StudentRepository studentRepository, SchoolRepository schoolRepository,
                          SectionRepository sectionRepository, AuditService auditService) {
        this.studentRepository = studentRepository;
        this.schoolRepository = schoolRepository;
        this.sectionRepository = sectionRepository;
        this.auditService = auditService;
    }

    /**
     * @throws ApiException 404 if the school does not exist, 409 if
     *                      {@code admissionNumber} is already taken.
     */
    @Transactional
    public Student create(CreateStudentRequest request) {
        String admissionNumber = request.admissionNumber().trim();

        if (!schoolRepository.existsById(request.schoolId())) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        if (studentRepository.existsByAdmissionNumber(admissionNumber)) {
            throw admissionConflict(admissionNumber);
        }

        Student student = new Student();
        student.setSchoolId(request.schoolId());
        student.setFullName(request.fullName().trim());
        student.setAdmissionNumber(admissionNumber);
        student.setDateOfBirth(request.dateOfBirth());
        student.setGuardianName(request.guardianName());
        student.setGuardianContact(request.guardianContact());
        student.setStatus(StudentStatus.ACTIVE);

        Student saved;
        try {
            saved = studentRepository.saveAndFlush(student);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent insert of the same admission number.
            throw admissionConflict(admissionNumber);
        }

        auditService.log(AuditActions.STUDENT_CREATED, AuditActions.STUDENT, saved.getId(),
                Map.of("admissionNumber", saved.getAdmissionNumber(), "fullName", saved.getFullName()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Student> list(Pageable pageable) {
        return studentRepository.findAll(pageable);
    }

    /** Lists students in one section. Used as an optional filter on the list. */
    @Transactional(readOnly = true)
    public Page<Student> listBySection(UUID sectionId, Pageable pageable) {
        return studentRepository.findBySectionId(sectionId, pageable);
    }

    /**
     * Lists students in one section for the attendance-marking roster.
     *
     * @throws ApiException 404 if the section does not exist.
     */
    @Transactional(readOnly = true)
    public Page<Student> listInSection(UUID sectionId, Pageable pageable) {
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        return studentRepository.findBySectionId(sectionId, pageable);
    }

    /**
     * Assigns (or reassigns) a student to a section. SCHOOL_ADMIN only.
     *
     * @throws ApiException 404 if the student or the section does not exist.
     */
    @Transactional
    public Student assignSection(UUID studentId, UUID sectionId) {
        Student student = requireStudent(studentId);
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        UUID previousSectionId = student.getSectionId();
        student.setSectionId(sectionId);
        Student saved = studentRepository.save(student);

        auditService.log(AuditActions.SECTION_ASSIGNED, AuditActions.STUDENT, studentId, details(
                "from", previousSectionId == null ? null : previousSectionId.toString(),
                "to", sectionId.toString()));
        return saved;
    }

    /**
     * @throws ApiException 404 if no such student. A nonexistent id is reported as
     *         missing, never as forbidden, so the API does not leak whether the
     *         record exists (plan section 2 / 7d).
     */
    @Transactional(readOnly = true)
    public Student get(UUID id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
    }

    /**
     * Updates a student's editable fields for a SCHOOL_ADMIN.
     *
     * <p>{@code admissionNumber} is intentionally NOT editable here: it is the
     * unique business key (UNIQUE(admission_number) since V18), so allowing a
     * change would re-open the duplicate-check / 409 path and risk rewriting a
     * student's identity. Admission-number corrections, if ever needed, should be
     * a separate, deliberate operation.
     *
     * @throws ApiException 404 if no such student, 400 if {@code status} is not
     *         one of ACTIVE / INACTIVE.
     */
    @Transactional
    public Student update(UUID id, UpdateStudentRequest request) {
        Student student = requireStudent(id);
        Map<String, Object> before = editableSnapshot(student);

        student.setFullName(request.fullName().trim());
        student.setGuardianName(blankToNull(request.guardianName()));
        student.setGuardianContact(blankToNull(request.guardianContact()));
        student.setStatus(requireValidStatus(request.status()));
        Student saved = studentRepository.save(student);

        auditService.log(AuditActions.STUDENT_UPDATED, AuditActions.STUDENT, id,
                Map.of("old", before, "new", editableSnapshot(saved)));
        return saved;
    }

    /** The editable fields of a student, for before/after audit context. */
    private static Map<String, Object> editableSnapshot(Student student) {
        return details(
                "fullName", student.getFullName(),
                "guardianName", student.getGuardianName(),
                "guardianContact", student.getGuardianContact(),
                "status", student.getStatus());
    }

    /**
     * Soft delete / reactivate: sets {@code status} without removing the row, so a
     * student who has left stays in the historical record (plan section 2).
     *
     * @throws ApiException 404 if no such student, 400 if {@code status} is not
     *         one of ACTIVE / INACTIVE.
     */
    @Transactional
    public Student changeStatus(UUID id, String status) {
        Student student = requireStudent(id);
        String previousStatus = student.getStatus();
        student.setStatus(requireValidStatus(status));
        Student saved = studentRepository.save(student);

        auditService.log(AuditActions.STUDENT_STATUS_CHANGED, AuditActions.STUDENT, id,
                details("from", previousStatus, "to", saved.getStatus()));
        return saved;
    }

    private Student requireStudent(UUID id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
    }

    private static String requireValidStatus(String raw) {
        String status = StudentStatus.normalizeOrNull(raw);
        if (status == null) {
            throw new ApiException("Status must be ACTIVE or INACTIVE", HttpStatus.BAD_REQUEST);
        }
        return status;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Null-tolerant map builder ({@link Map#of} rejects null values). Keys/values alternate. */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static ApiException admissionConflict(String admissionNumber) {
        return new ApiException(
                "A student with admission number '" + admissionNumber + "' already exists",
                HttpStatus.CONFLICT);
    }
}
