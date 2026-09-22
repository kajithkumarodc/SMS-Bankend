package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EnquirySourceRepository extends JpaRepository<EnquirySource, UUID> {

    List<EnquirySource> findAllByOrderByName();

    List<EnquirySource> findByActiveTrueOrderByName();

    boolean existsByName(String name);
}
