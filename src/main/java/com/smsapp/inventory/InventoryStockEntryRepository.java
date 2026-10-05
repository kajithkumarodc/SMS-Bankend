package com.smsapp.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InventoryStockEntryRepository extends JpaRepository<InventoryStockEntry, UUID> {

    List<InventoryStockEntry> findAllByOrderByEntryDateDescCreatedAtDesc();

    boolean existsBySupplierId(UUID supplierId);

    boolean existsByStoreId(UUID storeId);

    boolean existsByItemId(UUID itemId);
}
