package com.smsapp.portal;

import com.smsapp.attendance.AttendanceRecord;
import com.smsapp.attendance.AttendanceService;
import com.smsapp.common.ApiException;
import com.smsapp.exam.ExamMarkRepository.StudentExamResult;
import com.smsapp.exam.ExamService;
import com.smsapp.fee.FeeCollectionService;
import com.smsapp.fee.FeeDtos.ReceiptResponse;
import com.smsapp.fee.FeeDtos.StudentFeeStatementResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Self-service reads for the student and parent portals. Adds an OWNERSHIP layer:
 * a STUDENT may only reach their own linked record, and a PARENT only children
 * whose {@code guardian_user_id} is that parent -- both scoped by the query
 * itself, never by trusting an id from the request.
 */
@Service
public class PortalService {

    private static final String CHILD_NOT_FOUND = "Child not found";

    private final StudentRepository studentRepository;
    private final AttendanceService attendanceService;
    private final ExamService examService;
    private final InvoiceRepository invoiceRepository;
    private final BookLoanRepository bookLoanRepository;
    private final TransportService transportService;
    private final HostelService hostelService;
    private final FeeCollectionService feeCollectionService;

    public PortalService(StudentRepository studentRepository, AttendanceService attendanceService,
                         ExamService examService, InvoiceRepository invoiceRepository,
                         BookLoanRepository bookLoanRepository, TransportService transportService,
                         HostelService hostelService, FeeCollectionService feeCollectionService) {
        this.studentRepository = studentRepository;
        this.attendanceService = attendanceService;
        this.examService = examService;
        this.invoiceRepository = invoiceRepository;
        this.bookLoanRepository = bookLoanRepository;
        this.transportService = transportService;
        this.hostelService = hostelService;
        this.feeCollectionService = feeCollectionService;
    }

    /**
     * @throws ApiException 404 if no student record is linked to this STUDENT login
     *         (a clean "not found", not an error).
     */
    @Transactional(readOnly = true)
    public Student ownStudent(UUID studentUserId) {
        return studentRepository.findByStudentUserId(studentUserId)
                .orElseThrow(() -> new ApiException("No student record is linked to your account",
                        HttpStatus.NOT_FOUND));
    }

    /** The caller's own attendance history, strictly scoped to their own student id. */
    @Transactional(readOnly = true)
    public Page<AttendanceRecord> ownAttendance(UUID studentUserId, Pageable pageable) {
        Student self = ownStudent(studentUserId);
        return attendanceService.studentHistory(self.getId(), pageable);
    }

    /** All students linked to this PARENT login (may be empty; supports multiple children). */
    @Transactional(readOnly = true)
    public List<Student> children(UUID guardianUserId) {
        return studentRepository.findByGuardianUserIdOrderByFullName(guardianUserId);
    }

    /**
     * A parent's own child's attendance. The {@code studentId} from the URL is only
     * honoured if that student's {@code guardian_user_id} is this parent -- otherwise
     * 404, so it never leaks that another parent's student exists.
     */
    @Transactional(readOnly = true)
    public Page<AttendanceRecord> childAttendance(UUID guardianUserId, UUID studentId, Pageable pageable) {
        Student child = studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return attendanceService.studentHistory(child.getId(), pageable);
    }

    /** The caller's own exam results, strictly scoped to their own student id. */
    @Transactional(readOnly = true)
    public List<StudentExamResult> ownResults(UUID studentUserId) {
        Student self = ownStudent(studentUserId);
        return examService.studentResults(self.getId());
    }

    /**
     * A parent's own child's exam results. The {@code studentId} from the URL is only
     * honoured if that student's {@code guardian_user_id} is this parent -- otherwise 404.
     */
    @Transactional(readOnly = true)
    public List<StudentExamResult> childResults(UUID guardianUserId, UUID studentId) {
        Student child = studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return examService.studentResults(child.getId());
    }

    /**
     * A parent's own child's invoices (fee dues). The {@code studentId} from the URL is
     * only honoured if that student's {@code guardian_user_id} is this parent -- otherwise
     * 404, so it never leaks that another parent's student exists.
     */
    @Transactional(readOnly = true)
    public List<Invoice> childInvoices(UUID guardianUserId, UUID studentId) {
        Student child = studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return invoiceRepository.findByStudentIdOrderByCreatedAtDesc(child.getId());
    }

    /**
     * A parent's own child's fee statement (plan Phase 5 part J): totals + full invoice/payment
     * history. The {@code studentId} from the URL is only honoured if that student's
     * {@code guardian_user_id} is this parent -- otherwise 404.
     */
    @Transactional(readOnly = true)
    public StudentFeeStatementResponse childFeeStatement(UUID guardianUserId, UUID studentId) {
        return feeCollectionService.studentStatement(studentId, guardianUserId);
    }

    /**
     * A parent's own child's payment receipt. 404 if the payment doesn't exist or belongs to
     * another family's invoice.
     */
    @Transactional(readOnly = true)
    public ReceiptResponse childReceipt(UUID guardianUserId, UUID paymentId) {
        return feeCollectionService.receiptFor(paymentId, guardianUserId);
    }

    /** The caller's own library loan history, strictly scoped to their own student id. */
    @Transactional(readOnly = true)
    public List<LoanWithBook> ownLibrary(UUID studentUserId) {
        Student self = ownStudent(studentUserId);
        return bookLoanRepository.findLoanHistory(self.getId());
    }

    /**
     * A parent's own child's library loan history. The {@code studentId} from the URL
     * is only honoured if that student's {@code guardian_user_id} is this parent --
     * otherwise 404.
     */
    @Transactional(readOnly = true)
    public List<LoanWithBook> childLibrary(UUID guardianUserId, UUID studentId) {
        Student child = studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return bookLoanRepository.findLoanHistory(child.getId());
    }

    /**
     * The caller's own transport assignment (route + vehicles + driver info).
     *
     * @throws ApiException 404 if no student record is linked, or the student has no
     *         transport route assigned.
     */
    @Transactional(readOnly = true)
    public TransportAssignment ownTransport(UUID studentUserId) {
        Student self = ownStudent(studentUserId);
        return transportService.assignmentForRoute(self.getTransportRouteId());
    }

    /**
     * A parent's own child's transport assignment. The {@code studentId} from the URL
     * is only honoured if that student's {@code guardian_user_id} is this parent --
     * otherwise 404.
     *
     * @throws ApiException 404 if not this parent's child, or the child has no
     *         transport route assigned.
     */
    @Transactional(readOnly = true)
    public TransportAssignment childTransport(UUID guardianUserId, UUID studentId) {
        Student child = studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return transportService.assignmentForRoute(child.getTransportRouteId());
    }

    /**
     * The caller's own hostel allocation (block + room + roommates).
     *
     * @throws ApiException 404 if no student record is linked, or the student has no
     *         hostel room allocated.
     */
    @Transactional(readOnly = true)
    public HostelAllocation ownHostel(UUID studentUserId) {
        Student self = ownStudent(studentUserId);
        return hostelService.allocationForRoom(self.getId(), self.getHostelRoomId());
    }

    /**
     * A parent's own child's hostel allocation. The {@code studentId} from the URL is
     * only honoured if that student's {@code guardian_user_id} is this parent --
     * otherwise 404.
     *
     * @throws ApiException 404 if not this parent's child, or the child has no hostel
     *         room allocated.
     */
    @Transactional(readOnly = true)
    public HostelAllocation childHostel(UUID guardianUserId, UUID studentId) {
        Student child = studentRepository.findByIdAndGuardianUserId(studentId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return hostelService.allocationForRoom(child.getId(), child.getHostelRoomId());
    }
}
