package com.smsapp.student;

import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
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

    public StudentService(StudentRepository studentRepository, SchoolRepository schoolRepository) {
        this.studentRepository = studentRepository;
        this.schoolRepository = schoolRepository;
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

    private static ApiException admissionConflict(String admissionNumber) {
        return new ApiException(
                "A student with admission number '" + admissionNumber + "' already exists",
                HttpStatus.CONFLICT);
    }
}
