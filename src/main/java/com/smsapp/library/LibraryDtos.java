package com.smsapp.library;

import com.smsapp.library.BookLoanRepository.LoanWithBook;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the library API. Entities are never exposed directly (plan section 7.1d). */
final class LibraryDtos {

    private LibraryDtos() {
    }

    record CreateBookRequest(
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 200) String author,
            @Size(max = 20) String isbn,
            @NotNull @Min(1) Integer totalCopies) {
    }

    record BookResponse(
            UUID id,
            String title,
            String author,
            String isbn,
            int totalCopies,
            int availableCopies,
            OffsetDateTime createdAt) {

        static BookResponse from(LibraryBook book) {
            return new BookResponse(book.getId(), book.getTitle(), book.getAuthor(), book.getIsbn(),
                    book.getTotalCopies(), book.getAvailableCopies(), book.getCreatedAt());
        }
    }

    /** {@code studentId} comes from the request body; {@code bookId} identifies the title to issue. */
    record IssueLoanRequest(
            @NotNull UUID bookId,
            @NotNull UUID studentId) {
    }

    /** The loan row itself -- returned on issue / return. */
    record LoanResponse(
            UUID id,
            UUID bookId,
            UUID studentId,
            LocalDate issuedDate,
            LocalDate dueDate,
            LocalDate returnedDate) {

        static LoanResponse from(BookLoan loan) {
            return new LoanResponse(loan.getId(), loan.getBookId(), loan.getStudentId(),
                    loan.getIssuedDate(), loan.getDueDate(), loan.getReturnedDate());
        }
    }

    /** One row of a loan history -- carries the book's title + author (same shape the portal returns). */
    record LoanHistoryView(
            UUID id,
            UUID bookId,
            String bookTitle,
            String bookAuthor,
            LocalDate issuedDate,
            LocalDate dueDate,
            LocalDate returnedDate) {

        static LoanHistoryView from(LoanWithBook loan) {
            return new LoanHistoryView(loan.getId(), loan.getBookId(), loan.getBookTitle(), loan.getBookAuthor(),
                    loan.getIssuedDate(), loan.getDueDate(), loan.getReturnedDate());
        }
    }
}
