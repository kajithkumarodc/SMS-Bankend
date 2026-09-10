package com.smsapp.library;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.library.BookLoanRepository.ActiveLoan;
import com.smsapp.library.BookLoanRepository.LoanWithBook;
import com.smsapp.library.LibraryDtos.CreateBookRequest;
import com.smsapp.library.LibraryDtos.IssueLoanRequest;
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

/**
 * Library catalog + issue/return (plan section 2). Every read and write is scoped
 * by {@code tenant_id} on top of the RLS policy. {@code available_copies} is kept
 * in step with the loans here: -1 on issue, +1 on return.
 */
@Service
public class LibraryService {

    /** Standard loan period. Configurable per tenant is a later slice. */
    static final int LOAN_PERIOD_DAYS = 14;

    private final LibraryBookRepository bookRepository;
    private final BookLoanRepository loanRepository;
    private final StudentRepository studentRepository;
    private final AuditService auditService;

    public LibraryService(LibraryBookRepository bookRepository, BookLoanRepository loanRepository,
                          StudentRepository studentRepository, AuditService auditService) {
        this.bookRepository = bookRepository;
        this.loanRepository = loanRepository;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
    }

    // --- Catalog ---------------------------------------------------

    @Transactional
    public LibraryBook addBook(UUID tenantId, CreateBookRequest request) {
        LibraryBook book = new LibraryBook();
        book.setTenantId(tenantId);
        book.setTitle(request.title().trim());
        book.setAuthor(request.author().trim());
        book.setIsbn(request.isbn() == null || request.isbn().isBlank() ? null : request.isbn().trim());
        book.setTotalCopies(request.totalCopies());
        book.setAvailableCopies(request.totalCopies());
        LibraryBook saved = bookRepository.save(book);

        auditService.log(AuditActions.LIBRARY_BOOK_ADDED, AuditActions.LIBRARY_BOOK, saved.getId(),
                Map.of("title", saved.getTitle(), "totalCopies", saved.getTotalCopies()));
        return saved;
    }

    /** The whole catalog, or -- when {@code q} is given -- titles/authors matching it. */
    @Transactional(readOnly = true)
    public Page<LibraryBook> listBooks(UUID tenantId, String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return bookRepository.findByTenantId(tenantId, pageable);
        }
        return bookRepository.search(tenantId, q.trim(), pageable);
    }

    // --- Issue / return ------------------------------------------

    /**
     * @throws ApiException 404 if the book or the student is not in the caller's
     *         tenant, 400 if no copies are available.
     */
    @Transactional
    public BookLoan issue(UUID tenantId, IssueLoanRequest request) {
        LibraryBook book = bookRepository.findByIdAndTenantId(request.bookId(), tenantId)
                .orElseThrow(() -> new ApiException("Book not found", HttpStatus.NOT_FOUND));
        if (studentRepository.findByIdAndTenantId(request.studentId(), tenantId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        if (book.getAvailableCopies() <= 0) {
            throw new ApiException("No copies of this book are available", HttpStatus.BAD_REQUEST);
        }

        book.setAvailableCopies(book.getAvailableCopies() - 1);
        bookRepository.save(book);

        LocalDate issued = LocalDate.now();
        BookLoan loan = new BookLoan();
        loan.setTenantId(tenantId);
        loan.setBookId(book.getId());
        loan.setStudentId(request.studentId());
        loan.setIssuedDate(issued);
        loan.setDueDate(issued.plusDays(LOAN_PERIOD_DAYS));
        BookLoan saved = loanRepository.save(loan);

        auditService.log(AuditActions.LIBRARY_BOOK_ISSUED, AuditActions.BOOK_LOAN, saved.getId(),
                Map.of("bookId", book.getId().toString(), "studentId", request.studentId().toString(),
                        "dueDate", saved.getDueDate().toString()));
        return saved;
    }

    /**
     * @throws ApiException 404 if the loan is not in the caller's tenant, 409 if it
     *         has already been returned.
     */
    @Transactional
    public BookLoan returnLoan(UUID tenantId, UUID loanId) {
        BookLoan loan = loanRepository.findByIdAndTenantId(loanId, tenantId)
                .orElseThrow(() -> new ApiException("Loan not found", HttpStatus.NOT_FOUND));
        if (loan.getReturnedDate() != null) {
            throw new ApiException("This loan has already been returned", HttpStatus.CONFLICT);
        }

        loan.setReturnedDate(LocalDate.now());
        loanRepository.save(loan);

        // Put the copy back. Guard against exceeding total (should never happen).
        bookRepository.findByIdAndTenantId(loan.getBookId(), tenantId).ifPresent(book -> {
            book.setAvailableCopies(Math.min(book.getAvailableCopies() + 1, book.getTotalCopies()));
            bookRepository.save(book);
        });

        auditService.log(AuditActions.LIBRARY_BOOK_RETURNED, AuditActions.BOOK_LOAN, loan.getId(),
                Map.of("bookId", loan.getBookId().toString(), "studentId", loan.getStudentId().toString()));
        return loan;
    }

    // --- Loan history --------------------------------------------

    /**
     * A student's loan history for staff. @throws ApiException 404 if the student
     * is not in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public List<LoanWithBook> loanHistoryForStudent(UUID tenantId, UUID studentId) {
        if (studentRepository.findByIdAndTenantId(studentId, tenantId).isEmpty()) {
            throw new ApiException("Student not found", HttpStatus.NOT_FOUND);
        }
        return loanRepository.findLoanHistory(tenantId, studentId);
    }

    /** Portal use: the caller's ownership of {@code studentId} has already been proven. */
    @Transactional(readOnly = true)
    public List<LoanWithBook> loanHistoryForOwnedStudent(UUID tenantId, UUID studentId) {
        return loanRepository.findLoanHistory(tenantId, studentId);
    }

    /** Every not-yet-returned loan in the tenant, for the staff "active loans" view. */
    @Transactional(readOnly = true)
    public List<ActiveLoan> activeLoans(UUID tenantId) {
        return loanRepository.findActiveLoans(tenantId);
    }
}
