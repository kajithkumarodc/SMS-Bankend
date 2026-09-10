package com.smsapp.portal;

import com.smsapp.attendance.AttendanceRecord;
import com.smsapp.attendance.AttendanceService;
import com.smsapp.common.ApiException;
import com.smsapp.exam.ExamMarkRepository.StudentExamResult;
import com.smsapp.exam.ExamService;
import com.smsapp.fee.Invoice;
import com.smsapp.fee.InvoiceRepository;
import com.smsapp.hostel.HostelAllocation;
import com.smsapp.hostel.HostelService;
import com.smsapp.library.BookLoanRepository;
import com.smsapp.library.BookLoanRepository.LoanWithBook;
import com.smsapp.student.Student;
import com.smsapp.transport.TransportAssignment;
import com.smsapp.transport.TransportService;
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

    @Mock
    private ExamService examService;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private BookLoanRepository bookLoanRepository;

    @Mock
    private TransportService transportService;

    @Mock
    private HostelService hostelService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID studentUserId = UUID.randomUUID();
    private final UUID guardianUserId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    private PortalService service() {
        return new PortalService(studentRepository, attendanceService, examService, invoiceRepository,
                bookLoanRepository, transportService, hostelService);
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

    @Test
    void ownResultsIsScopedToTheCallersOwnStudentId() {
        when(studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(examService.studentResults(tenantId, studentId)).thenReturn(List.of());

        service().ownResults(tenantId, studentUserId);

        verify(examService).studentResults(tenantId, studentId);
    }

    @Test
    void ownResultsIs404WhenNoRecordIsLinked() {
        when(studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().ownResults(tenantId, studentUserId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(examService, never()).studentResults(any(), any());
    }

    @Test
    void childResultsIsAllowedForTheParentsOwnChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(examService.studentResults(tenantId, studentId)).thenReturn(List.<StudentExamResult>of());

        service().childResults(tenantId, guardianUserId, studentId);

        verify(examService).studentResults(tenantId, studentId);
    }

    @Test
    void childResultsIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childResults(tenantId, guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(examService, never()).studentResults(any(), any());
    }

    @Test
    void childInvoicesIsAllowedForTheParentsOwnChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(invoiceRepository.findByTenantIdAndStudentIdOrderByCreatedAtDesc(tenantId, studentId))
                .thenReturn(List.<Invoice>of());

        service().childInvoices(tenantId, guardianUserId, studentId);

        verify(invoiceRepository).findByTenantIdAndStudentIdOrderByCreatedAtDesc(tenantId, studentId);
    }

    @Test
    void childInvoicesIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childInvoices(tenantId, guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(invoiceRepository, never()).findByTenantIdAndStudentIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void ownLibraryIsScopedToTheCallersOwnStudentId() {
        when(studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(bookLoanRepository.findLoanHistory(tenantId, studentId)).thenReturn(List.<LoanWithBook>of());

        service().ownLibrary(tenantId, studentUserId);

        verify(bookLoanRepository).findLoanHistory(tenantId, studentId);
    }

    @Test
    void childLibraryIsAllowedForTheParentsOwnChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(bookLoanRepository.findLoanHistory(tenantId, studentId)).thenReturn(List.<LoanWithBook>of());

        service().childLibrary(tenantId, guardianUserId, studentId);

        verify(bookLoanRepository).findLoanHistory(tenantId, studentId);
    }

    @Test
    void childLibraryIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childLibrary(tenantId, guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(bookLoanRepository, never()).findLoanHistory(any(), any());
    }

    @Test
    void ownTransportResolvesTheAssignmentForTheCallersOwnRoute() {
        UUID routeId = UUID.randomUUID();
        Student self = linkedStudent();
        self.setTransportRouteId(routeId);
        when(studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId))
                .thenReturn(Optional.of(self));
        TransportAssignment assignment = new TransportAssignment(routeId, "Route 1", List.of());
        when(transportService.assignmentForRoute(tenantId, routeId)).thenReturn(assignment);

        assertThat(service().ownTransport(tenantId, studentUserId)).isSameAs(assignment);
        verify(transportService).assignmentForRoute(tenantId, routeId);
    }

    @Test
    void childTransportIsAllowedForTheParentsOwnChild() {
        UUID routeId = UUID.randomUUID();
        Student child = linkedStudent();
        child.setTransportRouteId(routeId);
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.of(child));
        when(transportService.assignmentForRoute(tenantId, routeId))
                .thenReturn(new TransportAssignment(routeId, "Route 1", List.of()));

        service().childTransport(tenantId, guardianUserId, studentId);

        verify(transportService).assignmentForRoute(tenantId, routeId);
    }

    @Test
    void childTransportIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childTransport(tenantId, guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(transportService, never()).assignmentForRoute(any(), any());
    }

    @Test
    void ownHostelResolvesTheAllocationForTheCallersOwnRoom() {
        UUID roomId = UUID.randomUUID();
        Student self = linkedStudent();
        self.setHostelRoomId(roomId);
        when(studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId))
                .thenReturn(Optional.of(self));
        HostelAllocation allocation =
                new HostelAllocation(UUID.randomUUID(), "Block A", roomId, "A-101", 3, List.of());
        when(hostelService.allocationForRoom(tenantId, studentId, roomId)).thenReturn(allocation);

        assertThat(service().ownHostel(tenantId, studentUserId)).isSameAs(allocation);
        verify(hostelService).allocationForRoom(tenantId, studentId, roomId);
    }

    @Test
    void childHostelIsAllowedForTheParentsOwnChild() {
        UUID roomId = UUID.randomUUID();
        Student child = linkedStudent();
        child.setHostelRoomId(roomId);
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.of(child));
        when(hostelService.allocationForRoom(tenantId, studentId, roomId))
                .thenReturn(new HostelAllocation(UUID.randomUUID(), "Block A", roomId, "A-101", 3, List.of()));

        service().childHostel(tenantId, guardianUserId, studentId);

        verify(hostelService).allocationForRoom(tenantId, studentId, roomId);
    }

    @Test
    void childHostelIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childHostel(tenantId, guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(hostelService, never()).allocationForRoom(any(), any(), any());
    }
}
