package com.smsapp.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InventoryIssueRepository extends JpaRepository<InventoryIssue, UUID> {

    List<InventoryIssue> findAllByOrderByIssueDateDescCreatedAtDesc();

    boolean existsByItemId(UUID itemId);
}
