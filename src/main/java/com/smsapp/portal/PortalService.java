package com.smsapp.portal;

import com.smsapp.attendance.AttendanceRecord;
import com.smsapp.attendance.AttendanceService;
import com.smsapp.common.ApiException;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Self-service reads for the student and parent portals. Adds an OWNERSHIP layer
 * on top of tenant isolation: a STUDENT may only reach their own linked record,
 * and a PARENT only children whose {@code guardian_user_id} is that parent --
 * both scoped by the query itself, never by trusting an id from the request.
 */
@Service
public class PortalService {

    private final StudentRepository studentRepository;
    private final AttendanceService attendanceService;

    public PortalService(StudentRepository studentRepository, AttendanceService attendanceService) {
        this.studentRepository = studentRepository;
        this.attendanceService = attendanceService;
    }

    /**
     * @throws ApiException 404 if no student record is linked to this STUDENT login
     *         (a clean "not found", not an error).
     */
    @Transactional(readOnly = true)
    public Student ownStudent(UUID tenantId, UUID studentUserId) {
        return studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId)
                .orElseThrow(() -> new ApiException("No student record is linked to your account",
                        HttpStatus.NOT_FOUND));
    }

    /** The caller's own attendance history, strictly scoped to their own student id. */
    @Transactional(readOnly = true)
    public Page<AttendanceRecord> ownAttendance(UUID tenantId, UUID studentUserId, Pageable pageable) {
        Student self = ownStudent(tenantId, studentUserId);
        return attendanceService.studentHistory(tenantId, self.getId(), pageable);
    }

    /** All students linked to this PARENT login (may be empty; supports multiple children). */
    @Transactional(readOnly = true)
    public List<Student> children(UUID tenantId, UUID guardianUserId) {
        return studentRepository.findByTenantIdAndGuardianUserIdOrderByFullName(tenantId, guardianUserId);
    }

    /**
     * A parent's own child's attendance. The {@code studentId} from the URL is only
     * honoured if that student's {@code guardian_user_id} is this parent -- otherwise
     * 404, so it never leaks that another tenant's / another parent's student exists.
     */
    @Transactional(readOnly = true)
    public Page<AttendanceRecord> childAttendance(UUID tenantId, UUID guardianUserId, UUID studentId,
                                                 Pageable pageable) {
        Student child = studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId)
                .orElseThrow(() -> new ApiException("Child not found", HttpStatus.NOT_FOUND));
        return attendanceService.studentHistory(tenantId, child.getId(), pageable);
    }
}
