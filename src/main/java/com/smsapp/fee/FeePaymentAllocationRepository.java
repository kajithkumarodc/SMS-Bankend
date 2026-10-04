package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FeePaymentAllocationRepository extends JpaRepository<FeePaymentAllocation, UUID> {

    List<FeePaymentAllocation> findByPaymentIdIn(Collection<UUID> paymentIds);

    List<FeePaymentAllocation> findByPaymentId(UUID paymentId);
}
