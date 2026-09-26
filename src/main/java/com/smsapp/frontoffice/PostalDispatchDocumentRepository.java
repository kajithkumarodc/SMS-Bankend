package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostalDispatchDocumentRepository extends JpaRepository<PostalDispatchDocument, UUID> {

    List<PostalDispatchDocument> findByDispatchIdOrderByUploadedAt(UUID dispatchId);

    List<PostalDispatchDocument> findByDispatchIdInOrderByUploadedAt(Collection<UUID> dispatchIds);

    Optional<PostalDispatchDocument> findByIdAndDispatchId(UUID id, UUID dispatchId);

    long countByDispatchId(UUID dispatchId);
}
