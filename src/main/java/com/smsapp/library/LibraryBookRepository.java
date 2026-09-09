package com.smsapp.library;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface LibraryBookRepository extends JpaRepository<LibraryBook, UUID> {

    Page<LibraryBook> findByTenantId(UUID tenantId, Pageable pageable);

    /** Catalog search over title + author, case-insensitive substring. */
    @Query("select b from LibraryBook b where b.tenantId = :tenantId and ("
            + "lower(b.title) like lower(concat('%', :q, '%')) or "
            + "lower(b.author) like lower(concat('%', :q, '%')))")
    Page<LibraryBook> search(@Param("tenantId") UUID tenantId, @Param("q") String q, Pageable pageable);

    Optional<LibraryBook> findByIdAndTenantId(UUID id, UUID tenantId);
}
