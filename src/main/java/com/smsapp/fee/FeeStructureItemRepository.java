package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeeStructureItemRepository extends JpaRepository<FeeStructureItem, UUID> {

    List<FeeStructureItem> findByFeeStructureIdOrderBySequenceOrder(UUID feeStructureId);
}
