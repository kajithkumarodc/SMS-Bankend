package com.smsapp.attendance;

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

    @Mock
    private AttendanceRepository attendanceRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private SectionRepository sectionRepository;

    @Mock
    private AuditService auditService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID teacherId = UUID.randomUUID();

    private AttendanceService service() {
        return new AttendanceService(attendanceRepository, studentRepository, sectionRepository, auditService);
    }

    private void studentExists() {
        lenient().when(studentRepository.findByIdAndTenantId(studentId, tenantId))
                .thenReturn(Optional.of(new Student()));
    }

    private MarkAttendanceRequest request(LocalDate date, String status) {
        return new MarkAttendanceRequest(studentId, date, status);
    }

    @Test
    void marksANewRecordWhenNoneExistsForThatStudentAndDate() {
        studentExists();
        LocalDate today = LocalDate.now();
        when(attendanceRepository.findByTenantIdAndStudentIdAndDate(tenantId, studentId, today))
                .thenReturn(Optional.empty());
        when(attendanceRepository.save(any(AttendanceRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkResult result = service().mark(tenantId, teacherId, request(today, "present"));

        assertThat(result.created()).isTrue();
        assertThat(result.entry().getTenantId()).isEqualTo(tenantId);
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
        existing.setTenantId(tenantId);
        existing.setStudentId(studentId);
        existing.setDate(today);
        existing.setStatus(AttendanceStatus.ABSENT);
        existing.setMarkedBy(UUID.randomUUID());
        when(attendanceRepository.findByTenantIdAndStudentIdAndDate(tenantId, studentId, today))
                .thenReturn(Optional.of(existing));
        when(attendanceRepository.save(any(AttendanceRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MarkResult result = service().mark(tenantId, teacherId, request(today, "LATE"));

        assertThat(result.created()).isFalse();
        assertThat(result.entry().getId()).isEqualTo(existing.getId());
        assertThat(result.entry().getStatus()).isEqualTo(AttendanceStatus.LATE);
        assertThat(result.entry().getMarkedBy()).isEqualTo(teacherId);
    }

    @Test
    void rejectsAFutureDateWith400() {
        studentExists();

        assertThatThrownBy(() -> service().mark(tenantId, teacherId,
                request(LocalDate.now().plusDays(1), "PRESENT")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void rejectsAnUnknownStatusWith400() {
        assertThatThrownBy(() -> service().mark(tenantId, teacherId,
                request(LocalDate.now(), "HOLIDAY")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void reportsAStudentOutsideTheTenantAs404() {
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().mark(tenantId, teacherId, request(LocalDate.now(), "PRESENT")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void studentHistoryReportsAnUnknownStudentAs404() {
        when(studentRepository.findByIdAndTenantId(studentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().studentHistory(tenantId, studentId, org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listForSectionOnDateReportsAnotherTenantsSectionAs404() {
        UUID sectionId = UUID.randomUUID();
        when(sectionRepository.existsByIdAndTenantId(sectionId, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> service().listForSectionOnDate(tenantId, sectionId, LocalDate.now()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(attendanceRepository, never()).findForSectionOnDate(any(), any(), any());
    }

    @Test
    void listForSectionOnDateReturnsTheSectionsRecordsWhenSectionIsInTenant() {
        UUID sectionId = UUID.randomUUID();
        AttendanceRecord rec = new AttendanceRecord();
        rec.setTenantId(tenantId);
        LocalDate today = LocalDate.now();
        when(sectionRepository.existsByIdAndTenantId(sectionId, tenantId)).thenReturn(true);
        when(attendanceRepository.findForSectionOnDate(tenantId, sectionId, today))
                .thenReturn(java.util.List.of(rec));

        assertThat(service().listForSectionOnDate(tenantId, sectionId, today)).containsExactly(rec);
    }
}
