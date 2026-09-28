package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeeDiscountRepository extends JpaRepository<FeeDiscount, UUID> {

    boolean existsByFeeStructureId(UUID feeStructureId);

    List<FeeDiscount> findAllByOrderByName();

    List<FeeDiscount> findByStatusOrderByName(String status);
}
