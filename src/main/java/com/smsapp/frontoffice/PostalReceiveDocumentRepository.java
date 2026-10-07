package com.smsapp.frontoffice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostalReceiveDocumentRepository extends JpaRepository<PostalReceiveDocument, UUID> {

    List<PostalReceiveDocument> findByReceiveIdOrderByUploadedAt(UUID receiveId);

    List<PostalReceiveDocument> findByReceiveIdInOrderByUploadedAt(Collection<UUID> receiveIds);

    Optional<PostalReceiveDocument> findByIdAndReceiveId(UUID id, UUID receiveId);

    long countByReceiveId(UUID receiveId);
}
