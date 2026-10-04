package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffDocumentRepository extends JpaRepository<StaffDocument, UUID> {

    List<StaffDocument> findByStaffProfileIdIn(Collection<UUID> staffProfileIds);

    List<StaffDocument> findByStaffProfileId(UUID staffProfileId);

    Optional<StaffDocument> findByStaffProfileIdAndKind(UUID staffProfileId, String kind);
}
