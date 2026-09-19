package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    /**
     * A staff member's own leave request history, newest first -- used by
     * {@code /me/leave-requests}, and reused for the SCHOOL_ADMIN per-staff view
     * (the query itself carries no ownership assumption; the caller decides whose
     * {@code staffUserId} to pass).
     */
    List<LeaveRequest> findByStaffUserIdOrderByCreatedAtDesc(UUID staffUserId);

    /** Every leave request, newest first -- the SCHOOL_ADMIN unfiltered view. */
    List<LeaveRequest> findAllByOrderByCreatedAtDesc();

    /** Every leave request in one status, newest first -- e.g. the PENDING approval queue. */
    List<LeaveRequest> findByStatusOrderByCreatedAtDesc(String status);

    /** One staff member's leave requests in one status, newest first. */
    List<LeaveRequest> findByStaffUserIdAndStatusOrderByCreatedAtDesc(UUID staffUserId, String status);
}
