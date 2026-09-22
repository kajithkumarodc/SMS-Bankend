package com.smsapp.fee;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    /** Webhook: resolve the invoice a Razorpay order was created for. */
    Optional<Invoice> findByRazorpayOrderId(String razorpayOrderId);

    /** Duplicate-assignment guard (plan Phase 5 part C): has this student already been billed against this structure? */
    boolean existsByStudentIdAndFeeStructureId(UUID studentId, UUID feeStructureId);

    /** Bulk assignment: which of these students already have an invoice for this structure (to skip, not duplicate). */
    @Query("select i.studentId from Invoice i where i.feeStructureId = :feeStructureId and i.studentId in :studentIds")
    List<UUID> studentIdsAlreadyInvoiced(@Param("feeStructureId") UUID feeStructureId, @Param("studentIds") List<UUID> studentIds);

    /**
     * Row-locked read for the payment-collection path (plan Phase 5 part N: concurrent collection
     * attempts against the same invoice must never both succeed against a balance that only covers
     * one of them). Held until the enclosing {@code @Transactional} method commits/rolls back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id")
    Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);
}
