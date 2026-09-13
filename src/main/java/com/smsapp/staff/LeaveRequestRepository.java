package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    Optional<LeaveRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * A staff member's own leave request history, newest first -- used by
     * {@code /me/leave-requests}, and reused for the SCHOOL_ADMIN per-staff view
     * (the query itself carries no ownership assumption; the caller decides whose
     * {@code staffUserId} to pass).
     */
    List<LeaveRequest> findByTenantIdAndStaffUserIdOrderByCreatedAtDesc(UUID tenantId, UUID staffUserId);

    /** Every leave request in the tenant, newest first -- the SCHOOL_ADMIN unfiltered view. */
    List<LeaveRequest> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    /** Every leave request in one status, newest first -- e.g. the PENDING approval queue. */
    List<LeaveRequest> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);

    /** One staff member's leave requests in one status, newest first. */
    List<LeaveRequest> findByTenantIdAndStaffUserIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, UUID staffUserId, String status);
}
