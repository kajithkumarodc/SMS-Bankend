package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EnquiryFollowUpRepository extends JpaRepository<EnquiryFollowUp, UUID> {

    List<EnquiryFollowUp> findByEnquiryIdOrderByFollowUpDateDescCreatedAtDesc(UUID enquiryId);
}
