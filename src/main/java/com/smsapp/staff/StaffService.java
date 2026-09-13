package com.smsapp.staff;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.staff.LeaveDtos.CreateLeaveRequestRequest;
import com.smsapp.staff.StaffDtos.CreateStaffProfileRequest;
import com.smsapp.staff.StaffDtos.UpdateStaffProfileRequest;
import com.smsapp.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff profiles + leave requests (plan section 2, Staff Management). Every
 * read and write is explicitly scoped by {@code tenant_id} on top of the RLS
 * policy. A cross-tenant user / profile / leave-request reference is reported
 * as 404, never 403, so the API never leaks that the row exists -- the one
 * exception is the leave-request ownership check itself, which is a genuine
 * permission boundary (a TEACHER filing on someone else's behalf), not a
 * tenant leak, so it is reported as 403.
 */
@Service
public class StaffService {

    private static final String STAFF_PROFILE_NOT_FOUND = "Staff profile not found";

    private final StaffProfileRepository staffProfileRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public StaffService(StaffProfileRepository staffProfileRepository,
                        LeaveRequestRepository leaveRequestRepository,
                        UserRepository userRepository,
                        AuditService auditService) {
        this.staffProfileRepository = staffProfileRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    // --- Staff profiles -------------------------------------------

    /**
     * @throws ApiException 404 if {@code userId} is not a user in the caller's tenant,
     *         409 if that user already has a staff profile, or if {@code employeeCode}
     *         is already used in the tenant.
     */
    @Transactional
    public StaffProfile createProfile(UUID tenantId, CreateStaffProfileRequest request) {
        if (!userRepository.existsByIdAndTenantId(request.userId(), tenantId)) {
            throw new ApiException("User not found", HttpStatus.NOT_FOUND);
        }
        if (staffProfileRepository.existsByTenantIdAndUserId(tenantId, request.userId())) {
            throw new ApiException("This user already has a staff profile", HttpStatus.CONFLICT);
        }
        String employeeCode = request.employeeCode().trim();
        if (staffProfileRepository.existsByTenantIdAndEmployeeCode(tenantId, employeeCode)) {
            throw employeeCodeConflict(employeeCode);
        }

        StaffProfile profile = new StaffProfile();
        profile.setTenantId(tenantId);
        profile.setUserId(request.userId());
        profile.setEmployeeCode(employeeCode);
        profile.setDepartment(blankToNull(request.department()));
        profile.setDesignation(blankToNull(request.designation()));
        profile.setDateOfJoining(request.dateOfJoining());
        profile.setSalaryAmount(request.salaryAmount());
        profile.setStatus(StaffProfileStatus.ACTIVE);

        StaffProfile saved;
        try {
            saved = staffProfileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent insert of the same user or employee code.
            if (staffProfileRepository.existsByTenantIdAndUserId(tenantId, request.userId())) {
                throw new ApiException("This user already has a staff profile", HttpStatus.CONFLICT);
            }
            throw employeeCodeConflict(employeeCode);
        }

        auditService.log(AuditActions.STAFF_PROFILE_CREATED, AuditActions.STAFF_PROFILE, saved.getId(),
                Map.of("employeeCode", saved.getEmployeeCode(), "userId", saved.getUserId().toString()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<StaffProfile> listProfiles(UUID tenantId) {
        return staffProfileRepository.findByTenantIdOrderByEmployeeCode(tenantId);
    }

    /**
     * Updates a staff profile's editable fields. {@code employeeCode} is intentionally
     * NOT editable here -- it is the tenant-unique business key, same reasoning as
     * {@code students.admission_number} (see {@code StudentService#update}).
     *
     * @throws ApiException 404 if no such profile in the caller's tenant, 400 if
     *         {@code status} is not one of ACTIVE / INACTIVE.
     */
    @Transactional
    public StaffProfile updateProfile(UUID tenantId, UUID id, UpdateStaffProfileRequest request) {
        StaffProfile profile = requireProfile(tenantId, id);
        Map<String, Object> before = editableSnapshot(profile);

        profile.setDepartment(blankToNull(request.department()));
        profile.setDesignation(blankToNull(request.designation()));
        profile.setDateOfJoining(request.dateOfJoining());
        profile.setSalaryAmount(request.salaryAmount());
        profile.setStatus(requireValidProfileStatus(request.status()));
        StaffProfile saved = staffProfileRepository.save(profile);

        auditService.log(AuditActions.STAFF_PROFILE_UPDATED, AuditActions.STAFF_PROFILE, id,
                Map.of("old", before, "new", editableSnapshot(saved)));
        return saved;
    }

    private static Map<String, Object> editableSnapshot(StaffProfile profile) {
        return details(
                "department", profile.getDepartment(),
                "designation", profile.getDesignation(),
                "dateOfJoining", profile.getDateOfJoining() == null ? null : profile.getDateOfJoining().toString(),
                "salaryAmount", profile.getSalaryAmount(),
                "status", profile.getStatus());
    }

    // --- Leave requests ---------------------------------------------

    /**
     * Files a leave request against a staff profile. {@code actingUserId} is the
     * caller's own user id when they hold TEACHER (so the request must be for
     * their own profile), or {@code null} when they hold SCHOOL_ADMIN and may file
     * on behalf of any staff member in the tenant.
     *
     * @throws ApiException 404 if the staff profile is not in the caller's tenant,
     *         403 if a non-admin caller is filing for someone else's profile,
     *         400 if {@code endDate} is before {@code startDate}.
     */
    @Transactional
    public LeaveRequest createLeaveRequest(UUID tenantId, UUID staffProfileId, UUID actingUserId,
                                           CreateLeaveRequestRequest request) {
        StaffProfile profile = requireProfile(tenantId, staffProfileId);
        if (actingUserId != null && !profile.getUserId().equals(actingUserId)) {
            throw new ApiException("You may only request leave for yourself", HttpStatus.FORBIDDEN);
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new ApiException("End date must not be before the start date", HttpStatus.BAD_REQUEST);
        }

        LeaveRequest leaveRequest = new LeaveRequest();
        leaveRequest.setTenantId(tenantId);
        leaveRequest.setStaffUserId(profile.getUserId());
        leaveRequest.setLeaveType(request.leaveType().trim());
        leaveRequest.setStartDate(request.startDate());
        leaveRequest.setEndDate(request.endDate());
        leaveRequest.setStatus(LeaveRequestStatus.PENDING);
        leaveRequest.setReason(blankToNull(request.reason()));
        LeaveRequest saved = leaveRequestRepository.save(leaveRequest);

        auditService.log(AuditActions.LEAVE_REQUEST_CREATED, AuditActions.LEAVE_REQUEST, saved.getId(),
                Map.of("staffUserId", saved.getStaffUserId().toString(), "leaveType", saved.getLeaveType()));
        return saved;
    }

    /**
     * Approves or rejects a leave request. SCHOOL_ADMIN only (enforced at the controller).
     *
     * @throws ApiException 404 if the leave request is not in the caller's tenant,
     *         400 if {@code status} is not APPROVED or REJECTED.
     */
    @Transactional
    public LeaveRequest decideLeaveRequest(UUID tenantId, UUID id, String status) {
        String decision = LeaveRequestStatus.normalizeDecisionOrNull(status);
        if (decision == null) {
            throw new ApiException("Status must be APPROVED or REJECTED", HttpStatus.BAD_REQUEST);
        }
        LeaveRequest leaveRequest = leaveRequestRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException("Leave request not found", HttpStatus.NOT_FOUND));

        String previousStatus = leaveRequest.getStatus();
        leaveRequest.setStatus(decision);
        LeaveRequest saved = leaveRequestRepository.save(leaveRequest);

        auditService.log(AuditActions.LEAVE_REQUEST_DECIDED, AuditActions.LEAVE_REQUEST, id,
                details("from", previousStatus, "to", decision));
        return saved;
    }

    /** A staff member's own leave request history, newest first. */
    @Transactional(readOnly = true)
    public List<LeaveRequest> ownLeaveRequests(UUID tenantId, UUID staffUserId) {
        return leaveRequestRepository.findByTenantIdAndStaffUserIdOrderByCreatedAtDesc(tenantId, staffUserId);
    }

    private StaffProfile requireProfile(UUID tenantId, UUID id) {
        return staffProfileRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException(STAFF_PROFILE_NOT_FOUND, HttpStatus.NOT_FOUND));
    }

    private static String requireValidProfileStatus(String raw) {
        String status = StaffProfileStatus.normalizeOrNull(raw);
        if (status == null) {
            throw new ApiException("Status must be ACTIVE or INACTIVE", HttpStatus.BAD_REQUEST);
        }
        return status;
    }

    private static ApiException employeeCodeConflict(String employeeCode) {
        return new ApiException(
                "A staff member with employee code '" + employeeCode + "' already exists",
                HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Null-tolerant map builder ({@link Map#of} rejects null values). Keys/values alternate. */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
