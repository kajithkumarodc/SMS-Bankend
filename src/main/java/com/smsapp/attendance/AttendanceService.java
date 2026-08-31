package com.smsapp.attendance;

import com.smsapp.attendance.AttendanceDtos.MarkAttendanceRequest;
import com.smsapp.common.ApiException;
import com.smsapp.student.StudentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;

    public AttendanceService(AttendanceRepository attendanceRepository, StudentRepository studentRepository) {
        this.attendanceRepository = attendanceRepository;
        this.studentRepository = studentRepository;
    }

    /** True when {@code record} was newly created; false when an existing record was updated. */
    public record MarkResult(AttendanceRecord record, boolean created) {
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

        AttendanceRecord record = attendanceRepository
                .findByTenantIdAndStudentIdAndDate(tenantId, request.studentId(), request.date())
                .orElse(null);
        boolean created = record == null;
        if (created) {
            record = new AttendanceRecord();
            record.setTenantId(tenantId);
            record.setStudentId(request.studentId());
            record.setDate(request.date());
        }
        record.setStatus(status);
        record.setMarkedBy(markedBy);
        return new MarkResult(attendanceRepository.save(record), created);
    }

    @Transactional(readOnly = true)
    public Page<AttendanceRecord> listByDate(UUID tenantId, LocalDate date, Pageable pageable) {
        return attendanceRepository.findByTenantIdAndDate(tenantId, date, pageable);
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
