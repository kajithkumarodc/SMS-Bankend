package com.smsapp.library;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface LibraryBookRepository extends JpaRepository<LibraryBook, UUID> {

    /** Catalog search over title + author, case-insensitive substring. */
    @Query("select b from LibraryBook b where "
            + "lower(b.title) like lower(concat('%', :q, '%')) or "
            + "lower(b.author) like lower(concat('%', :q, '%'))")
    Page<LibraryBook> search(@Param("q") String q, Pageable pageable);
}
