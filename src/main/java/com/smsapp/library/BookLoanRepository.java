package com.smsapp.library;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface BookLoanRepository extends JpaRepository<BookLoan, UUID> {

    Optional<BookLoan> findByIdAndTenantId(UUID id, UUID tenantId);

    /** One student's loan history, newest issue first, with the book's title + author joined in. */
    @Query("select l.id as id, l.bookId as bookId, b.title as bookTitle, b.author as bookAuthor, "
            + "l.issuedDate as issuedDate, l.dueDate as dueDate, l.returnedDate as returnedDate "
            + "from BookLoan l, LibraryBook b "
            + "where l.bookId = b.id and l.tenantId = :tenantId and l.studentId = :studentId "
            + "order by l.issuedDate desc, l.id")
    List<LoanWithBook> findLoanHistory(@Param("tenantId") UUID tenantId, @Param("studentId") UUID studentId);

    /** Every loan in the tenant that has not been returned yet, soonest due first, with book + student joined in. */
    @Query("select l.id as id, l.bookId as bookId, b.title as bookTitle, "
            + "l.studentId as studentId, s.fullName as studentName, "
            + "l.issuedDate as issuedDate, l.dueDate as dueDate "
            + "from BookLoan l, LibraryBook b, com.smsapp.student.Student s "
            + "where l.bookId = b.id and l.studentId = s.id and l.tenantId = :tenantId and l.returnedDate is null "
            + "order by l.dueDate asc, l.id")
    List<ActiveLoan> findActiveLoans(@Param("tenantId") UUID tenantId);

    interface LoanWithBook {
        UUID getId();

        UUID getBookId();

        String getBookTitle();

        String getBookAuthor();

        LocalDate getIssuedDate();

        LocalDate getDueDate();

        LocalDate getReturnedDate();
    }

    /** One currently-issued loan, for the staff "active loans" view. */
    interface ActiveLoan {
        UUID getId();

        UUID getBookId();

        String getBookTitle();

        UUID getStudentId();

        String getStudentName();

        LocalDate getIssuedDate();

        LocalDate getDueDate();
    }
}
