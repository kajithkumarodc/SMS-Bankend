package com.smsapp.announcement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    Page<Announcement> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    /** The few most recent, for the dashboard summary. */
    List<Announcement> findTop3ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Announcement> findByIdAndTenantId(UUID id, UUID tenantId);
}
