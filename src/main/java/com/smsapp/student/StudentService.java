package com.smsapp.student;

import com.smsapp.academics.SectionRepository;
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

import java.util.UUID;

@Service
public class StudentService {

    private final StudentRepository studentRepository;
    private final SchoolRepository schoolRepository;
    private final SectionRepository sectionRepository;

    public StudentService(StudentRepository studentRepository, SchoolRepository schoolRepository,
                          SectionRepository sectionRepository) {
        this.studentRepository = studentRepository;
        this.schoolRepository = schoolRepository;
        this.sectionRepository = sectionRepository;
    }

    /**
     * Creates a student for {@code tenantId}. Runs in a transaction so the RLS
     * session variable is set; every lookup is also explicitly tenant-filtered.
     *
     * @throws ApiException 404 if the school does not belong to the caller's tenant
     *                      (a school from another tenant must not be observable),
     *                      409 if {@code admissionNumber} is already taken in the tenant.
     */
    @Transactional
    public Student create(UUID tenantId, CreateStudentRequest request) {
        String admissionNumber = request.admissionNumber().trim();

        if (!schoolRepository.existsByIdAndTenantId(request.schoolId(), tenantId)) {
            throw new ApiException("School not found", HttpStatus.NOT_FOUND);
        }
        if (studentRepository.existsByTenantIdAndAdmissionNumber(tenantId, admissionNumber)) {
            throw admissionConflict(admissionNumber);
        }

        Student student = new Student();
        student.setTenantId(tenantId);
        student.setSchoolId(request.schoolId());
        student.setFullName(request.fullName().trim());
        student.setAdmissionNumber(admissionNumber);
        student.setDateOfBirth(request.dateOfBirth());
        student.setGuardianName(request.guardianName());
        student.setGuardianContact(request.guardianContact());
        student.setStatus(StudentStatus.ACTIVE);

        try {
            return studentRepository.saveAndFlush(student);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent insert of the same admission number.
            throw admissionConflict(admissionNumber);
        }
    }

    @Transactional(readOnly = true)
    public Page<Student> list(UUID tenantId, Pageable pageable) {
        return studentRepository.findByTenantId(tenantId, pageable);
    }

    /** Lists students in one section, still tenant-scoped underneath. */
    @Transactional(readOnly = true)
    public Page<Student> listBySection(UUID tenantId, UUID sectionId, Pageable pageable) {
        return studentRepository.findByTenantIdAndSectionId(tenantId, sectionId, pageable);
    }

    /**
     * Assigns (or reassigns) a student to a section. SCHOOL_ADMIN only.
     *
     * @throws ApiException 404 if the student is not in the caller's tenant, or if the
     *         section is not in the caller's tenant (another tenant's section must not
     *         be observable -- reported as missing, never forbidden).
     */
    @Transactional
    public Student assignSection(UUID tenantId, UUID studentId, UUID sectionId) {
        Student student = requireStudent(tenantId, studentId);
        if (!sectionRepository.existsByIdAndTenantId(sectionId, tenantId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        student.setSectionId(sectionId);
        return studentRepository.save(student);
    }

    /**
     * @throws ApiException 404 if no such student in the caller's tenant. A student
     *         belonging to another tenant is reported as missing, never as forbidden,
     *         so the API does not leak that the record exists (plan section 2 / 7d).
     */
    @Transactional(readOnly = true)
    public Student get(UUID tenantId, UUID id) {
        return studentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException("Student not found", HttpStatus.NOT_FOUND));
    }

    /**
     * Updates a student's editable fields for a SCHOOL_ADMIN.
     *
     * <p>{@code admissionNumber} is intentionally NOT editable here: it is the
     * tenant-unique business key (UNIQUE(tenant_id, admission_number) in V5), so
     * allowing a change would re-open the duplicate-check / 409 path and risk
     * rewriting a student's identity. Admission-number corrections, if ever
     * needed, should be a separate, deliberate operation.
     *
     * @throws ApiException 404 if no such student in the caller's tenant (cross-tenant
     *         records are reported as missing, same as {@link #get}), 400 if {@code status}
     *         is not one of ACTIVE / INACTIVE.
     */
    @Transactional
    public Student update(UUID tenantId, UUID id, UpdateStudentRequest request) {
        Student student = requireStudent(tenantId, id);
        student.setFullName(request.fullName().trim());
        student.setGuardianName(blankToNull(request.guardianName()));
        student.setGuardianContact(blankToNull(request.guardianContact()));
        student.setStatus(requireValidStatus(request.status()));
        return studentRepository.save(student);
    }

    /**
     * Soft delete / reactivate: sets {@code status} without removing the row, so a
     * student who has left stays in the historical record (plan section 2).
     *
     * @throws ApiException 404 if no such student in the caller's tenant, 400 if
     *         {@code status} is not one of ACTIVE / INACTIVE.
     */
    @Transactional
    public Student changeStatus(UUID tenantId, UUID id, String status) {
        Student student = requireStudent(tenantId, id);
        student.setStatus(requireValidStatus(status));
        return studentRepository.save(student);
    }

    private Student requireStudent(UUID tenantId, UUID id) {
        return studentRepository.findByIdAndTenantId(id, tenantId)
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

    private static ApiException admissionConflict(String admissionNumber) {
        return new ApiException(
                "A student with admission number '" + admissionNumber + "' already exists",
                HttpStatus.CONFLICT);
    }
}
