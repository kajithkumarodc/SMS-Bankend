package com.smsapp.library;

import com.smsapp.common.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One issue of a {@link LibraryBook} to a student. {@code returnedDate} is null while the book is out. */
@Entity
@Table(name = "book_loans")
@Getter
@Setter
@NoArgsConstructor
public class BookLoan extends TenantScopedEntity {

    @Column(name = "book_id", nullable = false, updatable = false)
    private UUID bookId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "issued_date", nullable = false, updatable = false)
    private LocalDate issuedDate;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "returned_date")
    private LocalDate returnedDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
