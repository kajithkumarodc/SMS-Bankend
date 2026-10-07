package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface PostalReceiveRepository extends JpaRepository<PostalReceive, UUID>, JpaSpecificationExecutor<PostalReceive> {
}
