package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EnquiryReferenceRepository extends JpaRepository<EnquiryReference, UUID> {

    List<EnquiryReference> findByActiveTrueOrderByName();

    boolean existsByName(String name);
}
