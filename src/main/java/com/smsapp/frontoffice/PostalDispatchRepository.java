package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface PostalDispatchRepository extends JpaRepository<PostalDispatch, UUID>, JpaSpecificationExecutor<PostalDispatch> {
}
