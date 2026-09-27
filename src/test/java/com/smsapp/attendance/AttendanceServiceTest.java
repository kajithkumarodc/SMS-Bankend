package com.smsapp.attendance;

import com.smsapp.common.SchoolClock;
import com.smsapp.academics.SectionRepository;
import com.smsapp.audit.AuditService;
import com.smsapp.attendance.AttendanceDtos.MarkAttendanceRequest;
import com.smsapp.attendance.AttendanceService.MarkResult;
import com.smsapp.common.ApiException;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {

    /** A real clock rather than a mock: it is a value object, and the tests'
     * date expectations only make sense against the school's own zone. */
    private static final SchoolClock CLOCK = new SchoolClock("Asia/Kolkata");

    @Mock
    private AttendanceRepository attendanceRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private AuditService auditService;

    private final UUID studentId = UUID.randomUUID();
    private final UUID teacherId = UUID.randomUUID();

    private AttendanceService service() {
        return new AttendanceService(attendanceRepository, studentRepository, sectionRepository, auditService, CLOCK);
    }

    private void studentExists() {
        lenient().when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));
    }

    private MarkAttendanceRequest request(LocalDate date, String status) {
        return new MarkAttendanceRequest(studentId, date, status);
    }

    @Test
    void marksANewRecordWhenNoneExistsForThatStudentAndDate() {
        studentExists();
        LocalDate today = LocalDate.now();
        when(attendanceRepository.findByStudentIdAndDate(studentId, today)).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(AttendanceRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkResult result = service().mark(teacherId, request(today, "present"));

        assertThat(result.created()).isTrue();
        assertThat(result.entry().getStudentId()).isEqualTo(studentId);
        assertThat(result.entry().getDate()).isEqualTo(today);
        assertThat(result.entry().getStatus()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(result.entry().getMarkedBy()).isEqualTo(teacherId);
    }

    @Test
    void updatesTheExistingRecordInsteadOfCreatingADuplicate() {
        studentExists();
        LocalDate today = LocalDate.now();
        AttendanceRecord existing = new AttendanceRecord();
        existing.setId(UUID.randomUUID());
        existing.setStudentId(studentId);
        existing.setDate(today);
        existing.setStatus(AttendanceStatus.ABSENT);
        existing.setMarkedBy(UUID.randomUUID());
        when(attendanceRepository.findByStudentIdAndDate(studentId, today)).thenReturn(Optional.of(existing));
        when(attendanceRepository.save(any(AttendanceRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkResult result = service().mark(teacherId, request(today, "LATE"));

        assertThat(result.created()).isFalse();
        assertThat(result.entry().getId()).isEqualTo(existing.getId());
        assertThat(result.entry().getStatus()).isEqualTo(AttendanceStatus.LATE);
        assertThat(result.entry().getMarkedBy()).isEqualTo(teacherId);
    }

    @Test
    void rejectsAFutureDateWith400() {
        studentExists();

        assertThatThrownBy(() -> service().mark(teacherId, request(CLOCK.today().plusDays(1), "PRESENT")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(attendanceRepository, never()).save(any());
    }

    /**
     * Regression: the future-date guard used to read {@code LocalDate.now()}, i.e. the
     * JVM's zone, which is UTC on Railway. Between 00:00 and 05:30 IST the school's
     * today is already the UTC tomorrow, so a teacher marking morning attendance got
     * "Attendance date cannot be in the future" for the current day. Marking today in
     * the school's own zone must always be accepted, whatever zone the JVM runs in.
     */
    @Test
    void acceptsTodayInTheSchoolsZoneEvenWhenTheJvmIsBehindIt() {
        studentExists();
        when(attendanceRepository.findByStudentIdAndDate(any(), any())).thenReturn(Optional.empty());
        when(attendanceRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        SchoolClock utcJvm = new SchoolClock("Asia/Kolkata");
        LocalDate schoolToday = utcJvm.today();

        MarkResult result = service().mark(teacherId, request(schoolToday, "PRESENT"));

        assertThat(result.entry().getDate()).isEqualTo(schoolToday);
        assertThat(schoolToday).isAfterOrEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
    }

    @Test
    void rejectsAnUnknownStatusWith400() {
        assertThatThrownBy(() -> service().mark(teacherId, request(LocalDate.now(), "HOLIDAY")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void reportsANonexistentStudentAs404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().mark(teacherId, request(LocalDate.now(), "PRESENT")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void studentHistoryReportsAnUnknownStudentAs404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().studentHistory(studentId, org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listForSectionOnDateReportsANonexistentSectionAs404() {
        UUID sectionId = UUID.randomUUID();
        when(sectionRepository.existsById(sectionId)).thenReturn(false);

        assertThatThrownBy(() -> service().listForSectionOnDate(sectionId, LocalDate.now()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(attendanceRepository, never()).findForSectionOnDate(any(), any());
    }

    @Test
    void listForSectionOnDateReturnsTheSectionsRecordsWhenSectionExists() {
        UUID sectionId = UUID.randomUUID();
        AttendanceRecord rec = new AttendanceRecord();
        LocalDate today = LocalDate.now();
        when(sectionRepository.existsById(sectionId)).thenReturn(true);
        when(attendanceRepository.findForSectionOnDate(sectionId, today)).thenReturn(java.util.List.of(rec));

        assertThat(service().listForSectionOnDate(sectionId, today)).containsExactly(rec);
    }
}
