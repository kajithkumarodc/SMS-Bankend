package com.smsapp.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InventorySupplierRepository extends JpaRepository<InventorySupplier, UUID> {

    List<InventorySupplier> findAllByOrderByName();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
