package com.smsapp.library;

import com.smsapp.common.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** One catalogued title. {@code availableCopies} tracks how many are not currently on loan. */
@Entity
@Table(name = "library_books")
@Getter
@Setter
@NoArgsConstructor
public class LibraryBook extends TenantScopedEntity {

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, length = 200)
    private String author;

    @Column(length = 20)
    private String isbn;

    @Column(name = "total_copies", nullable = false, updatable = false)
    private int totalCopies;

    @Column(name = "available_copies", nullable = false)
    private int availableCopies;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
