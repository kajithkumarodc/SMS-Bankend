package com.smsapp.attendance;

import com.smsapp.academics.SectionRepository;
import com.smsapp.attendance.AttendanceDtos.MarkAttendanceRequest;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.student.StudentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final SectionRepository sectionRepository;
    private final AuditService auditService;

    public AttendanceService(AttendanceRepository attendanceRepository, StudentRepository studentRepository,
                             SectionRepository sectionRepository, AuditService auditService) {
        this.attendanceRepository = attendanceRepository;
        this.studentRepository = studentRepository;
        this.sectionRepository = sectionRepository;
        this.auditService = auditService;
    }

    /** {@code created} is true when the record was newly inserted, false when an existing one was updated. */
    public record MarkResult(AttendanceRecord entry, boolean created) {
    }

    /**
     * Marks (or re-marks) attendance for one student on one date. If a record
     * already exists for that student+date it is updated in place -- a teacher
     * correcting a same-day mistake, not a duplicate error (plan section 2).
     *
     * @throws ApiException 400 if {@code date} is in the future or {@code status} is
     *         not PRESENT / ABSENT / LATE; 404 if the student is not in the caller's
     *         tenant (reported as missing, never forbidden -- no existence leak).
     */
    @Transactional
    public MarkResult mark(UUID tenantId, UUID markedBy, MarkAttendanceRequest request) {
        String status = AttendanceStatus.normalizeOrNull(request.status());
        if (status == null) {
            throw new ApiException("Status must be PRESENT, ABSENT or LATE", HttpStatus.BAD_REQUEST);
        }
        if (request.date().isAfter(LocalDate.now())) {
            throw new ApiException("Attendance date cannot be in the future", HttpStatus.BAD_REQUEST);
        }
        requireStudent(tenantId, request.studentId());

        AttendanceRecord entry = attendanceRepository
                .findByTenantIdAndStudentIdAndDate(tenantId, request.studentId(), request.date())
                .orElse(null);
        boolean created = entry == null;
        if (created) {
            entry = new AttendanceRecord();
            entry.setTenantId(tenantId);
            entry.setStudentId(request.studentId());
            entry.setDate(request.date());
        }
        entry.setStatus(status);
        entry.setMarkedBy(markedBy);
        AttendanceRecord saved = attendanceRepository.save(entry);

        auditService.log(created ? AuditActions.ATTENDANCE_MARKED : AuditActions.ATTENDANCE_CHANGED,
                AuditActions.ATTENDANCE_RECORD, saved.getId(),
                Map.of("studentId", saved.getStudentId().toString(),
                        "date", saved.getDate().toString(),
                        "status", saved.getStatus()));
        return new MarkResult(saved, created);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceRecord> listByDate(UUID tenantId, LocalDate date, Pageable pageable) {
        return attendanceRepository.findByTenantIdAndDate(tenantId, date, pageable);
    }

    /**
     * Attendance for one section on one date -- the marking roster's current state.
     * May be partial or empty if the section is not fully marked yet.
     *
     * @throws ApiException 404 if the section is not in the caller's tenant
     *         (another tenant's section must not be observable).
     */
    @Transactional(readOnly = true)
    public List<AttendanceRecord> listForSectionOnDate(UUID tenantId, UUID sectionId, LocalDate date) {
        if (!sectionRepository.existsByIdAndTenantId(sectionId, tenantId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        return attendanceRepository.findForSectionOnDate(tenantId, sectionId, date);
    }

    /**
     * @throws ApiException 404 if the student is not in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public Page<AttendanceRecord> studentHistory(UUID tenantId, UUID studentId, Pageable pageable) {
        requireStudent(tenantId, studentId);
        return attendanceRepository.findByTenantIdAndStudentIdOrderByDateDesc(tenantId, studentId, pageable);
    }

    private void requireStudent(UUID tenantId, UUID studentId) {
        if (studentRepository.findByIdAndTenantId(studentId, tenantId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
    }
}
