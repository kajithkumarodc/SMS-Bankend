package com.smsapp.portal;

import com.smsapp.attendance.AttendanceRecord;
import com.smsapp.attendance.AttendanceService;
import com.smsapp.common.ApiException;
import com.smsapp.exam.ExamMarkRepository.StudentExamResult;
import com.smsapp.exam.ExamService;
import com.smsapp.fee.FeeCollectionService;
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

    @Mock
    private FeeCollectionService feeCollectionService;

    private final UUID studentUserId = UUID.randomUUID();
    private final UUID guardianUserId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    private PortalService service() {
        return new PortalService(studentRepository, attendanceService, examService, invoiceRepository,
                bookLoanRepository, transportService, hostelService, feeCollectionService);
    }

    private Student linkedStudent() {
        Student student = new Student();
        student.setId(studentId);
        student.setFullName("Asha");
        student.setStudentUserId(studentUserId);
        student.setGuardianUserId(guardianUserId);
        return student;
    }

    @Test
    void ownStudentReturnsTheLinkedRecord() {
        when(studentRepository.findByStudentUserId(studentUserId)).thenReturn(Optional.of(linkedStudent()));

        assertThat(service().ownStudent(studentUserId).getId()).isEqualTo(studentId);
    }

    @Test
    void ownStudentIs404WhenNoRecordIsLinked() {
        when(studentRepository.findByStudentUserId(studentUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().ownStudent(studentUserId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ownAttendanceIsScopedToTheCallersOwnStudentId() {
        when(studentRepository.findByStudentUserId(studentUserId)).thenReturn(Optional.of(linkedStudent()));
        when(attendanceService.studentHistory(eq(studentId), any(Pageable.class))).thenReturn(Page.empty());

        service().ownAttendance(studentUserId, Pageable.unpaged());

        verify(attendanceService).studentHistory(studentId, Pageable.unpaged());
    }

    @Test
    void childrenReturnsEveryLinkedStudent() {
        Student a = linkedStudent();
        Student b = new Student();
        b.setId(UUID.randomUUID());
        when(studentRepository.findByGuardianUserIdOrderByFullName(guardianUserId)).thenReturn(List.of(a, b));

        assertThat(service().children(guardianUserId)).hasSize(2);
    }

    @Test
    void childAttendanceIsAllowedForTheParentsOwnChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(attendanceService.studentHistory(eq(studentId), any(Pageable.class)))
                .thenReturn(Page.<AttendanceRecord>empty());

        service().childAttendance(guardianUserId, studentId, Pageable.unpaged());

        verify(attendanceService).studentHistory(studentId, Pageable.unpaged());
    }

    @Test
    void childAttendanceIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childAttendance(guardianUserId, studentId, Pageable.unpaged()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(attendanceService, never()).studentHistory(any(), any());
    }

    @Test
    void ownResultsIsScopedToTheCallersOwnStudentId() {
        when(studentRepository.findByStudentUserId(studentUserId)).thenReturn(Optional.of(linkedStudent()));
        when(examService.studentResults(studentId)).thenReturn(List.of());

        service().ownResults(studentUserId);

        verify(examService).studentResults(studentId);
    }

    @Test
    void ownResultsIs404WhenNoRecordIsLinked() {
        when(studentRepository.findByStudentUserId(studentUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().ownResults(studentUserId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(examService, never()).studentResults(any());
    }

    @Test
    void childResultsIsAllowedForTheParentsOwnChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(examService.studentResults(studentId)).thenReturn(List.<StudentExamResult>of());

        service().childResults(guardianUserId, studentId);

        verify(examService).studentResults(studentId);
    }

    @Test
    void childResultsIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childResults(guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(examService, never()).studentResults(any());
    }

    @Test
    void childInvoicesIsAllowedForTheParentsOwnChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(invoiceRepository.findByStudentIdOrderByCreatedAtDesc(studentId)).thenReturn(List.<Invoice>of());

        service().childInvoices(guardianUserId, studentId);

        verify(invoiceRepository).findByStudentIdOrderByCreatedAtDesc(studentId);
    }

    @Test
    void childInvoicesIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childInvoices(guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(invoiceRepository, never()).findByStudentIdOrderByCreatedAtDesc(any());
    }

    @Test
    void ownLibraryIsScopedToTheCallersOwnStudentId() {
        when(studentRepository.findByStudentUserId(studentUserId)).thenReturn(Optional.of(linkedStudent()));
        when(bookLoanRepository.findLoanHistory(studentId)).thenReturn(List.<LoanWithBook>of());

        service().ownLibrary(studentUserId);

        verify(bookLoanRepository).findLoanHistory(studentId);
    }

    @Test
    void childLibraryIsAllowedForTheParentsOwnChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId))
                .thenReturn(Optional.of(linkedStudent()));
        when(bookLoanRepository.findLoanHistory(studentId)).thenReturn(List.<LoanWithBook>of());

        service().childLibrary(guardianUserId, studentId);

        verify(bookLoanRepository).findLoanHistory(studentId);
    }

    @Test
    void childLibraryIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childLibrary(guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(bookLoanRepository, never()).findLoanHistory(any());
    }

    @Test
    void ownTransportResolvesTheAssignmentForTheCallersOwnRoute() {
        UUID routeId = UUID.randomUUID();
        Student self = linkedStudent();
        self.setTransportRouteId(routeId);
        when(studentRepository.findByStudentUserId(studentUserId)).thenReturn(Optional.of(self));
        TransportAssignment assignment = new TransportAssignment(routeId, "Route 1", List.of());
        when(transportService.assignmentForRoute(routeId)).thenReturn(assignment);

        assertThat(service().ownTransport(studentUserId)).isSameAs(assignment);
        verify(transportService).assignmentForRoute(routeId);
    }

    @Test
    void childTransportIsAllowedForTheParentsOwnChild() {
        UUID routeId = UUID.randomUUID();
        Student child = linkedStudent();
        child.setTransportRouteId(routeId);
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.of(child));
        when(transportService.assignmentForRoute(routeId))
                .thenReturn(new TransportAssignment(routeId, "Route 1", List.of()));

        service().childTransport(guardianUserId, studentId);

        verify(transportService).assignmentForRoute(routeId);
    }

    @Test
    void childTransportIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childTransport(guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(transportService, never()).assignmentForRoute(any());
    }

    @Test
    void ownHostelResolvesTheAllocationForTheCallersOwnRoom() {
        UUID roomId = UUID.randomUUID();
        Student self = linkedStudent();
        self.setHostelRoomId(roomId);
        when(studentRepository.findByStudentUserId(studentUserId)).thenReturn(Optional.of(self));
        HostelAllocation allocation =
                new HostelAllocation(UUID.randomUUID(), "Block A", roomId, "A-101", 3, List.of());
        when(hostelService.allocationForRoom(studentId, roomId)).thenReturn(allocation);

        assertThat(service().ownHostel(studentUserId)).isSameAs(allocation);
        verify(hostelService).allocationForRoom(studentId, roomId);
    }

    @Test
    void childHostelIsAllowedForTheParentsOwnChild() {
        UUID roomId = UUID.randomUUID();
        Student child = linkedStudent();
        child.setHostelRoomId(roomId);
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.of(child));
        when(hostelService.allocationForRoom(studentId, roomId))
                .thenReturn(new HostelAllocation(UUID.randomUUID(), "Block A", roomId, "A-101", 3, List.of()));

        service().childHostel(guardianUserId, studentId);

        verify(hostelService).allocationForRoom(studentId, roomId);
    }

    @Test
    void childHostelIs404WhenTheStudentIsNotThisParentsChild() {
        when(studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().childHostel(guardianUserId, studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(hostelService, never()).allocationForRoom(any(), any());
    }
}
