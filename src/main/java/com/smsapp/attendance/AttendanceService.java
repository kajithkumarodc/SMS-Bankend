package com.smsapp.attendance;

import com.smsapp.academics.SectionRepository;
import com.smsapp.attendance.AttendanceDtos.BulkEntry;
import com.smsapp.attendance.AttendanceDtos.BulkRequest;
import com.smsapp.attendance.AttendanceDtos.MarkAttendanceRequest;
import com.smsapp.attendance.AttendanceDtos.RosterRow;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.SchoolClock;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.student.StudentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final SectionRepository sectionRepository;
    private final AuditService auditService;
    private final SchoolClock clock;

    public AttendanceService(AttendanceRepository attendanceRepository, StudentRepository studentRepository,
                             SectionRepository sectionRepository, AuditService auditService, SchoolClock clock) {
        this.attendanceRepository = attendanceRepository;
        this.studentRepository = studentRepository;
        this.sectionRepository = sectionRepository;
        this.auditService = auditService;
        this.clock = clock;
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
            throw new ApiException("Status must be PRESENT, ABSENT, LATE, HOLIDAY or HALF_DAY", HttpStatus.BAD_REQUEST);
        }
        // Against the school's own calendar day, not the JVM's -- on a UTC host a
        // morning mark in IST would otherwise be rejected as "in the future".
        if (request.date().isAfter(clock.today())) {
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

    /**
     * The active students of a section with the mark saved for {@code date}, if any -- the Student Attendance page.
     *
     * @throws ApiException 404 if the section does not exist.
     */
    @Transactional(readOnly = true)
    public List<RosterRow> roster(UUID sectionId, LocalDate date) {
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        Map<UUID, AttendanceRecord> marks = new HashMap<>();
        for (AttendanceRecord record : attendanceRepository.findForSectionOnDate(sectionId, date)) {
            marks.put(record.getStudentId(), record);
        }
        return studentRepository.findBySectionIdAndStatusOrderByFullName(sectionId, StudentStatus.ACTIVE).stream().map(s -> {
            AttendanceRecord mark = marks.get(s.getId());
            return new RosterRow(s.getId(), s.getAdmissionNumber(), s.getRollNumber(), s.getFullName(),
                    mark == null ? null : mark.getStatus(), mark == null ? null : mark.getDate(),
                    mark == null ? "MANUAL" : mark.getSource(), mark == null ? null : mark.getEntryTime(),
                    mark == null ? null : mark.getExitTime(), mark == null ? null : mark.getNote());
        }).toList();
    }

    /**
     * Saves the marks of one section's day in a single step; a student already marked that day is updated.
     *
     * @throws ApiException 400 if the date is in the future, a status is not valid, the exit time is not after the entry
     *         time, or a student is not an active member of the section; 404 if the section does not exist.
     */
    @Transactional
    public int saveBulk(UUID markedBy, BulkRequest request) {
        if (request.date().isAfter(LocalDate.now())) {
            throw new ApiException("Attendance date cannot be in the future", HttpStatus.BAD_REQUEST);
        }
        if (!sectionRepository.existsById(request.sectionId())) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        Set<UUID> members = studentRepository.findBySectionIdAndStatusOrderByFullName(request.sectionId(), StudentStatus.ACTIVE)
                .stream().map(Student::getId).collect(Collectors.toSet());
        for (BulkEntry entry : request.entries()) {
            if (AttendanceStatus.normalizeOrNull(entry.status()) == null) {
                throw new ApiException("Status must be PRESENT, ABSENT, LATE, HOLIDAY or HALF_DAY", HttpStatus.BAD_REQUEST);
            }
            if (!members.contains(entry.studentId())) {
                throw new ApiException("A student in the list does not belong to this section", HttpStatus.BAD_REQUEST);
            }
            if (entry.entryTime() != null && entry.exitTime() != null && !entry.exitTime().isAfter(entry.entryTime())) {
                throw new ApiException("The exit time must be after the entry time", HttpStatus.BAD_REQUEST);
            }
        }
        for (BulkEntry entry : request.entries()) {
            AttendanceRecord record = attendanceRepository.findByStudentIdAndDate(entry.studentId(), request.date()).orElse(null);
            boolean created = record == null;
            if (created) {
                record = new AttendanceRecord();
                record.setStudentId(entry.studentId());
                record.setDate(request.date());
            }
            record.setStatus(AttendanceStatus.normalizeOrNull(entry.status()));
            record.setEntryTime(entry.entryTime());
            record.setExitTime(entry.exitTime());
            String note = entry.note() == null ? null : entry.note().trim();
            record.setNote(note == null || note.isEmpty() ? null : note);
            record.setMarkedBy(markedBy);
            AttendanceRecord saved = attendanceRepository.save(record);
            auditService.log(created ? AuditActions.ATTENDANCE_MARKED : AuditActions.ATTENDANCE_CHANGED,
                    AuditActions.ATTENDANCE_RECORD, saved.getId(),
                    Map.of("studentId", saved.getStudentId().toString(), "date", saved.getDate().toString(),
                            "status", saved.getStatus()));
        }
        return request.entries().size();
    }
}
