package com.smsapp.expense;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExpenseHeadRepository extends JpaRepository<ExpenseHead, UUID> {

    List<ExpenseHead> findByActiveTrueOrderByName();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);
}
