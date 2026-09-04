package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByTenantIdAndStudentIdOrderByCreatedAtDesc(UUID tenantId, UUID studentId);

    Optional<Invoice> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Webhook: resolve the invoice a Razorpay order was created for (still tenant-scoped). */
    Optional<Invoice> findByTenantIdAndRazorpayOrderId(UUID tenantId, String razorpayOrderId);
}
