package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeeAdjustmentRepository extends JpaRepository<FeeAdjustment, UUID> {

    List<FeeAdjustment> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);
}
