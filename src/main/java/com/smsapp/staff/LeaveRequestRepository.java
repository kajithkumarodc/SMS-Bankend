package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
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

    long countByLeaveTypeId(UUID leaveTypeId);

    /** Keeps the leave type name shown on its requests (the text column) in step with a rename. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update LeaveRequest r set r.leaveType = :name where r.leaveTypeId = :leaveTypeId")
    int renameLeaveType(@org.springframework.data.repository.query.Param("leaveTypeId") UUID leaveTypeId,
                        @org.springframework.data.repository.query.Param("name") String name);

    /** Every leave request, latest leave date first -- the Approve Leave Request page. */
    List<LeaveRequest> findAllByOrderByStartDateDescCreatedAtDesc();

    List<LeaveRequest> findByStaffUserIdOrderByStartDateDescCreatedAtDesc(UUID staffUserId);

    /** A staff member's requests (other than the one status given) that touch the date range -- the overlap check. */
    List<LeaveRequest> findByStaffUserIdAndStatusNotAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID staffUserId, String status, LocalDate to, LocalDate from);

    /** Every leave request in one status, newest first -- e.g. the PENDING approval queue. */
    List<LeaveRequest> findByStatusOrderByCreatedAtDesc(String status);

    /** One staff member's leave requests in one status, newest first. */
    List<LeaveRequest> findByStaffUserIdAndStatusOrderByCreatedAtDesc(UUID staffUserId, String status);
}
