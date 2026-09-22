package com.smsapp.admission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdmissionApplicationDocumentRepository extends JpaRepository<AdmissionApplicationDocument, UUID> {

    List<AdmissionApplicationDocument> findByApplicationIdOrderByUploadedAtDesc(UUID applicationId);

    Optional<AdmissionApplicationDocument> findByIdAndApplicationId(UUID id, UUID applicationId);
}
