package com.smsapp.library;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.library.LibraryDtos.CreateBookRequest;
import com.smsapp.library.LibraryDtos.IssueLoanRequest;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LibraryServiceTest {

    @Mock
    private LibraryBookRepository bookRepository;

    @Mock
    private BookLoanRepository loanRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AuditService auditService;

    private LibraryService service() {
        return new LibraryService(bookRepository, loanRepository, studentRepository, auditService);
    }

    private final UUID bookId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID loanId = UUID.randomUUID();

    private LibraryBook book(int total, int available) {
        LibraryBook b = new LibraryBook();
        b.setId(bookId);
        b.setTitle("Refactoring");
        b.setAuthor("Fowler");
        b.setTotalCopies(total);
        b.setAvailableCopies(available);
        return b;
    }

    private BookLoan loan(LocalDate returnedDate) {
        BookLoan l = new BookLoan();
        l.setId(loanId);
        l.setBookId(bookId);
        l.setStudentId(studentId);
        l.setIssuedDate(LocalDate.now().minusDays(2));
        l.setDueDate(LocalDate.now().plusDays(12));
        l.setReturnedDate(returnedDate);
        return l;
    }

    // --- add book ------------------------------------------------

    @Test
    void addBookStartsWithAllCopiesAvailableAndAudits() {
        when(bookRepository.save(any(LibraryBook.class))).thenAnswer(inv -> {
            LibraryBook b = inv.getArgument(0);
            b.setId(bookId);
            return b;
        });

        LibraryBook created = service().addBook(new CreateBookRequest("  Refactoring  ", "  Fowler  ", "  978-0 ", 5));

        assertThat(created.getTitle()).isEqualTo("Refactoring");
        assertThat(created.getAuthor()).isEqualTo("Fowler");
        assertThat(created.getIsbn()).isEqualTo("978-0");
        assertThat(created.getTotalCopies()).isEqualTo(5);
        assertThat(created.getAvailableCopies()).isEqualTo(5);
        verify(auditService).log(eq(AuditActions.LIBRARY_BOOK_ADDED), eq(AuditActions.LIBRARY_BOOK), eq(bookId), anyMap());
    }

    @Test
    void addBookTreatsBlankIsbnAsNull() {
        when(bookRepository.save(any(LibraryBook.class))).thenAnswer(inv -> inv.getArgument(0));

        LibraryBook created = service().addBook(new CreateBookRequest("T", "A", "   ", 1));

        assertThat(created.getIsbn()).isNull();
    }

    // --- issue --------------------------------------------------

    @Test
    void issueDecrementsAvailableCopiesAndSetsA14DayDueDate() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book(3, 3)));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));
        when(loanRepository.save(any(BookLoan.class))).thenAnswer(inv -> inv.getArgument(0));

        BookLoan issued = service().issue(new IssueLoanRequest(bookId, studentId));

        ArgumentCaptor<LibraryBook> savedBook = ArgumentCaptor.forClass(LibraryBook.class);
        verify(bookRepository).save(savedBook.capture());
        assertThat(savedBook.getValue().getAvailableCopies()).isEqualTo(2);

        assertThat(issued.getBookId()).isEqualTo(bookId);
        assertThat(issued.getStudentId()).isEqualTo(studentId);
        assertThat(issued.getReturnedDate()).isNull();
        assertThat(issued.getDueDate()).isEqualTo(issued.getIssuedDate().plusDays(14));
        verify(auditService).log(eq(AuditActions.LIBRARY_BOOK_ISSUED), eq(AuditActions.BOOK_LOAN), any(), anyMap());
    }

    @Test
    void issueWhenNoCopiesAvailableReturns400() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book(2, 0)));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(new Student()));

        assertThatThrownBy(() -> service().issue(new IssueLoanRequest(bookId, studentId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(loanRepository, never()).save(any());
    }

    @Test
    void issueForNonexistentBookReturns404() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().issue(new IssueLoanRequest(bookId, studentId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(loanRepository, never()).save(any());
    }

    @Test
    void issueForNonexistentStudentReturns404() {
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book(3, 3)));
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().issue(new IssueLoanRequest(bookId, studentId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(loanRepository, never()).save(any());
        verify(bookRepository, never()).save(any());
    }

    // --- return ------------------------------------------------

    @Test
    void returnSetsReturnedDateAndPutsTheCopyBack() {
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan(null)));
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book(3, 1)));

        BookLoan returned = service().returnLoan(loanId);

        assertThat(returned.getReturnedDate()).isEqualTo(LocalDate.now());

        ArgumentCaptor<LibraryBook> savedBook = ArgumentCaptor.forClass(LibraryBook.class);
        verify(bookRepository).save(savedBook.capture());
        assertThat(savedBook.getValue().getAvailableCopies()).isEqualTo(2);
        verify(auditService).log(eq(AuditActions.LIBRARY_BOOK_RETURNED), eq(AuditActions.BOOK_LOAN), eq(loanId), anyMap());
    }

    @Test
    void returnCapsAvailableCopiesAtTheTotal() {
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan(null)));
        when(bookRepository.findById(bookId)).thenReturn(Optional.of(book(3, 3)));

        service().returnLoan(loanId);

        ArgumentCaptor<LibraryBook> savedBook = ArgumentCaptor.forClass(LibraryBook.class);
        verify(bookRepository).save(savedBook.capture());
        assertThat(savedBook.getValue().getAvailableCopies()).isEqualTo(3);
    }

    @Test
    void returningAnAlreadyReturnedLoanReturns409() {
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan(LocalDate.now().minusDays(1))));

        assertThatThrownBy(() -> service().returnLoan(loanId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(loanRepository, never()).save(any());
        verify(bookRepository, never()).save(any());
    }

    @Test
    void returnForNonexistentLoanReturns404() {
        when(loanRepository.findById(loanId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().returnLoan(loanId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- loan history -----------------------------------------

    @Test
    void staffLoanHistoryRejectsANonexistentStudentWith404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().loanHistoryForStudent(studentId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(loanRepository, never()).findLoanHistory(any());
    }

    @Test
    void activeLoansDelegatesToTheRepository() {
        when(loanRepository.findActiveLoans()).thenReturn(List.of());

        service().activeLoans();

        verify(loanRepository).findActiveLoans();
    }
}
