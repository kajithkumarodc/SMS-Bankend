package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    Optional<LeaveRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    /** A staff member's own leave request history, newest first -- used by {@code /me/leave-requests}. */
    List<LeaveRequest> findByTenantIdAndStaffUserIdOrderByCreatedAtDesc(UUID tenantId, UUID staffUserId);
}
