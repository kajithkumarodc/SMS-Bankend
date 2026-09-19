package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    /** Webhook: resolve the invoice a Razorpay order was created for. */
    Optional<Invoice> findByRazorpayOrderId(String razorpayOrderId);
}
