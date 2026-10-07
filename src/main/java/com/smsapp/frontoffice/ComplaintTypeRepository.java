package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ComplaintTypeRepository extends JpaRepository<ComplaintType, UUID> {

    List<ComplaintType> findByActiveTrueOrderByName();

    boolean existsByName(String name);
}
