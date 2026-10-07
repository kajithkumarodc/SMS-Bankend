package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface ComplaintRepository extends JpaRepository<Complaint, UUID>, JpaSpecificationExecutor<Complaint> {
}
