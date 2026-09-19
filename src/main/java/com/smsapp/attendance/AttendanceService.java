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
     *         not PRESENT / ABSENT / LATE; 404 if the student does not exist
     *         (reported as missing, never forbidden -- no existence leak).
     */
    @Transactional
    public MarkResult mark(UUID markedBy, MarkAttendanceRequest request) {
        String status = AttendanceStatus.normalizeOrNull(request.status());
        if (status == null) {
            throw new ApiException("Status must be PRESENT, ABSENT or LATE", HttpStatus.BAD_REQUEST);
        }
        if (request.date().isAfter(LocalDate.now())) {
            throw new ApiException("Attendance date cannot be in the future", HttpStatus.BAD_REQUEST);
        }
        requireStudent(request.studentId());

        AttendanceRecord entry = attendanceRepository
                .findByStudentIdAndDate(request.studentId(), request.date())
                .orElse(null);
        boolean created = entry == null;
        if (created) {
            entry = new AttendanceRecord();
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
    public Page<AttendanceRecord> listByDate(LocalDate date, Pageable pageable) {
        return attendanceRepository.findByDate(date, pageable);
    }

    /**
     * Attendance for one section on one date -- the marking roster's current state.
     * May be partial or empty if the section is not fully marked yet.
     *
     * @throws ApiException 404 if the section does not exist.
     */
    @Transactional(readOnly = true)
    public List<AttendanceRecord> listForSectionOnDate(UUID sectionId, LocalDate date) {
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        return attendanceRepository.findForSectionOnDate(sectionId, date);
    }

    /**
     * @throws ApiException 404 if the student does not exist.
     */
    @Transactional(readOnly = true)
    public Page<AttendanceRecord> studentHistory(UUID studentId, Pageable pageable) {
        requireStudent(studentId);
        return attendanceRepository.findByStudentIdOrderByDateDesc(studentId, pageable);
    }

    private void requireStudent(UUID studentId) {
        if (studentRepository.findById(studentId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
    }
}
