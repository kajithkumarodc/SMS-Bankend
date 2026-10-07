package com.smsapp.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    List<InventoryItem> findAllByOrderByName();

    List<InventoryItem> findByCategoryIdOrderByName(UUID categoryId);

    boolean existsByCategoryId(UUID categoryId);

    boolean existsByNameIgnoreCaseAndCategoryId(String name, UUID categoryId);

    boolean existsByNameIgnoreCaseAndCategoryIdAndIdNot(String name, UUID categoryId, UUID id);

    /** Locks the row so concurrent issues of the same item can't both take the last units. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.id = :id")
    Optional<InventoryItem> findForUpdate(@Param("id") UUID id);
}
