package com.smsapp.portal;

import com.smsapp.attendance.AttendanceRecord;
import com.smsapp.attendance.AttendanceService;
import com.smsapp.common.ApiException;
import com.smsapp.exam.ExamMarkRepository.StudentExamResult;
import com.smsapp.exam.ExamService;
import com.smsapp.fee.Invoice;
import com.smsapp.fee.InvoiceRepository;
import com.smsapp.library.BookLoanRepository;
import com.smsapp.library.BookLoanRepository.LoanWithBook;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Self-service reads for the student and parent portals. Adds an OWNERSHIP layer
 * on top of tenant isolation: a STUDENT may only reach their own linked record,
 * and a PARENT only children whose {@code guardian_user_id} is that parent --
 * both scoped by the query itself, never by trusting an id from the request.
 */
@Service
public class PortalService {

    private static final String CHILD_NOT_FOUND = "Child not found";

    private final StudentRepository studentRepository;
    private final AttendanceService attendanceService;
    private final ExamService examService;
    private final InvoiceRepository invoiceRepository;
    private final BookLoanRepository bookLoanRepository;

    public PortalService(StudentRepository studentRepository, AttendanceService attendanceService,
                         ExamService examService, InvoiceRepository invoiceRepository,
                         BookLoanRepository bookLoanRepository) {
        this.studentRepository = studentRepository;
        this.attendanceService = attendanceService;
        this.examService = examService;
        this.invoiceRepository = invoiceRepository;
        this.bookLoanRepository = bookLoanRepository;
    }

    /**
     * @throws ApiException 404 if no student record is linked to this STUDENT login
     *         (a clean "not found", not an error).
     */
    @Transactional(readOnly = true)
    public Student ownStudent(UUID tenantId, UUID studentUserId) {
        return studentRepository.findByTenantIdAndStudentUserId(tenantId, studentUserId)
                .orElseThrow(() -> new ApiException("No student record is linked to your account",
                        HttpStatus.NOT_FOUND));
    }

    /** The caller's own attendance history, strictly scoped to their own student id. */
    @Transactional(readOnly = true)
    public Page<AttendanceRecord> ownAttendance(UUID tenantId, UUID studentUserId, Pageable pageable) {
        Student self = ownStudent(tenantId, studentUserId);
        return attendanceService.studentHistory(tenantId, self.getId(), pageable);
    }

    /** All students linked to this PARENT login (may be empty; supports multiple children). */
    @Transactional(readOnly = true)
    public List<Student> children(UUID tenantId, UUID guardianUserId) {
        return studentRepository.findByTenantIdAndGuardianUserIdOrderByFullName(tenantId, guardianUserId);
    }

    /**
     * A parent's own child's attendance. The {@code studentId} from the URL is only
     * honoured if that student's {@code guardian_user_id} is this parent -- otherwise
     * 404, so it never leaks that another tenant's / another parent's student exists.
     */
    @Transactional(readOnly = true)
    public Page<AttendanceRecord> childAttendance(UUID tenantId, UUID guardianUserId, UUID studentId,
                                                 Pageable pageable) {
        Student child = studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return attendanceService.studentHistory(tenantId, child.getId(), pageable);
    }

    /** The caller's own exam results, strictly scoped to their own student id. */
    @Transactional(readOnly = true)
    public List<StudentExamResult> ownResults(UUID tenantId, UUID studentUserId) {
        Student self = ownStudent(tenantId, studentUserId);
        return examService.studentResults(tenantId, self.getId());
    }

    /**
     * A parent's own child's exam results. The {@code studentId} from the URL is only
     * honoured if that student's {@code guardian_user_id} is this parent -- otherwise 404.
     */
    @Transactional(readOnly = true)
    public List<StudentExamResult> childResults(UUID tenantId, UUID guardianUserId, UUID studentId) {
        Student child = studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return examService.studentResults(tenantId, child.getId());
    }

    /**
     * A parent's own child's invoices (fee dues). The {@code studentId} from the URL is
     * only honoured if that student's {@code guardian_user_id} is this parent -- otherwise
     * 404, so it never leaks that another parent's / another tenant's student exists.
     */
    @Transactional(readOnly = true)
    public List<Invoice> childInvoices(UUID tenantId, UUID guardianUserId, UUID studentId) {
        Student child = studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return invoiceRepository.findByTenantIdAndStudentIdOrderByCreatedAtDesc(tenantId, child.getId());
    }

    /** The caller's own library loan history, strictly scoped to their own student id. */
    @Transactional(readOnly = true)
    public List<LoanWithBook> ownLibrary(UUID tenantId, UUID studentUserId) {
        Student self = ownStudent(tenantId, studentUserId);
        return bookLoanRepository.findLoanHistory(tenantId, self.getId());
    }

    /**
     * A parent's own child's library loan history. The {@code studentId} from the URL
     * is only honoured if that student's {@code guardian_user_id} is this parent --
     * otherwise 404.
     */
    @Transactional(readOnly = true)
    public List<LoanWithBook> childLibrary(UUID tenantId, UUID guardianUserId, UUID studentId) {
        Student child = studentRepository.findByIdAndTenantIdAndGuardianUserId(studentId, tenantId, guardianUserId)
                .orElseThrow(() -> new ApiException(CHILD_NOT_FOUND, HttpStatus.NOT_FOUND));
        return bookLoanRepository.findLoanHistory(tenantId, child.getId());
    }
}
