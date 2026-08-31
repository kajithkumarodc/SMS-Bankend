package com.smsapp.portal;

import com.smsapp.attendance.AttendanceRecord;
import com.smsapp.attendance.AttendanceService;
import com.smsapp.common.ApiException;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortalServiceTest {

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AttendanceService attendanceService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();
    private final UUID guardianUserId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    private PortalService service() {
        return new PortalService(studentRepository, attendanceService);
    }

    private Student linkedStudent() {
        Student student = new Student();
        student.setId(studentId);
        student.setTenantId(tenantId);
        student.setFullName("Asha");
        student.setStudentUserId(studentUserId);
        student.setGuardianUserId(guardianUserId);
        return student;
    }

    @Test
    void ownStudentReturnsTheLinkedRecord() {
        when(studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId))
                .thenReturn(Optional.of(linkedStudent()));

        assertThat(service().ownStudent(tenantId, studentUserId).getId()).isEqualTo(studentId);
    }

    @Test
    void ownStudentIs404WhenNoRecordIsLinked() {
        when(studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().ownStudent(tenantId, studentUserId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ownAttendanceIsScopedToTheCallersOwnStudentId() {
        when(studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(attendanceService.studentHistory(eq(tenantId), eq(studentId), any(Pageable.class)))
                .thenReturn(Page.empty());

        service().ownAttendance(tenantId, studentUserId, Pageable.unpaged());

        verify(attendanceService).studentHistory(tenantId, studentId, Pageable.unpaged());
    }

    @Test
    void childrenReturnsEveryLinkedStudent() {
        Student a = linkedStudent();
        Student b = new Student();
        b.setId(UUID.randomUUID());
        when(studentRepository.findByTenantIdAndGuardianUserIdOrderByFullName(tenantId, guardianUserId))
                .thenReturn(List.of(a, b));

        assertThat(service().children(tenantId, guardianUserId)).hasSize(2);
    }

    @Test
    void childAttendanceIsAllowedForTheParentsOwnChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(attendanceService.studentHistory(eq(tenantId), eq(studentId), any(Pageable.class)))
                .thenReturn(Page.<AttendanceRecord>empty());

        service().childAttendance(tenantId, guardianUserId, studentId, Pageable.unpaged());

        verify(attendanceService).studentHistory(tenantId, studentId, Pageable.unpaged());
    }

    @Test
    void childAttendanceIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childAttendance(tenantId, guardianUserId, studentId, Pageable.unpaged()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(attendanceService, never()).studentHistory(any(), any(), any());
    }
}
