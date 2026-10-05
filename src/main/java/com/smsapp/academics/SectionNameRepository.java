package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SectionNameRepository extends JpaRepository<SectionName, UUID> {

    List<SectionName> findAllByOrderByName();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    java.util.Optional<SectionName> findByNameIgnoreCase(String name);
}
