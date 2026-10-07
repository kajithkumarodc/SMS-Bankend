package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FrontOfficePurposeRepository extends JpaRepository<FrontOfficePurpose, UUID> {

    List<FrontOfficePurpose> findByActiveTrueOrderByName();

    boolean existsByName(String name);
}
