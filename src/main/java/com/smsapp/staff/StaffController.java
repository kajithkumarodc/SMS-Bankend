package com.smsapp.staff;

import com.smsapp.staff.LeaveDtos.CreateLeaveRequestRequest;
import com.smsapp.staff.LeaveDtos.DecideLeaveRequestRequest;
import com.smsapp.staff.LeaveDtos.LeaveRequestResponse;
import com.smsapp.staff.StaffDtos.CreateStaffProfileRequest;
import com.smsapp.staff.StaffDtos.StaffProfileResponse;
import com.smsapp.staff.StaffDtos.UpdateStaffProfileRequest;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Staff profiles + leave requests (plan section 2). Profiles are created/updated
 * by a SCHOOL_ADMIN only. A leave request may be filed by the staff member
 * themselves (SCHOOL_ADMIN or TEACHER -- the two roles that carry a staff
 * profile today) or by a SCHOOL_ADMIN on any staff member's behalf; a TEACHER
 * filing for someone else's profile gets 403. Approving/rejecting is
 * SCHOOL_ADMIN only. A staff member's own leave history is served under
 * {@code /api/v1/me/leave-requests}, ownership-scoped by their own user id.
 */
@RestController
@RequestMapping("/api/v1")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    /** Create a staff profile for an existing user. SCHOOL_ADMIN only. 404 if the user is not in the tenant. */
    @PostMapping("/staff")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<StaffProfileResponse> createProfile(@Valid @RequestBody CreateStaffProfileRequest request,
                                                              Authentication authentication) {
        StaffProfile created = staffService.createProfile(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(StaffProfileResponse.from(created));
    }

    /** All staff profiles for the caller's tenant, by employee code. SCHOOL_ADMIN only -- salary is sensitive HR data. */
    @GetMapping("/staff")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    List<StaffProfileResponse> listProfiles(Authentication authentication) {
        return staffService.listProfiles(tenantId(authentication)).stream()
                .map(StaffProfileResponse::from).toList();
    }

    /** Update a staff profile's editable fields. SCHOOL_ADMIN only. 404 if not in the caller's tenant. */
    @PutMapping("/staff/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StaffProfileResponse updateProfile(@PathVariable UUID id,
                                              @Valid @RequestBody UpdateStaffProfileRequest request,
                                              Authentication authentication) {
        return StaffProfileResponse.from(staffService.updateProfile(tenantId(authentication), id, request));
    }

    /**
     * File a leave request against a staff profile -- the staff member themselves, or a
     * SCHOOL_ADMIN on their behalf. 404 if the profile is not in the caller's tenant,
     * 403 if a non-admin caller is filing for a profile that is not their own.
     */
    @PostMapping("/staff/{id}/leave-requests")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    public ResponseEntity<LeaveRequestResponse> createLeaveRequest(@PathVariable UUID id,
                                                                   @Valid @RequestBody CreateLeaveRequestRequest request,
                                                                   Authentication authentication) {
        LeaveRequest created = staffService.createLeaveRequest(
                tenantId(authentication), id, staffScope(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(LeaveRequestResponse.from(created));
    }

    /**
     * Approve or reject a leave request. SCHOOL_ADMIN only. 404 if not in the caller's
     * tenant, 400 if {@code status} is not APPROVED or REJECTED.
     */
    @PatchMapping("/leave-requests/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public LeaveRequestResponse decideLeaveRequest(@PathVariable UUID id,
                                                   @Valid @RequestBody DecideLeaveRequestRequest request,
                                                   Authentication authentication) {
        return LeaveRequestResponse.from(
                staffService.decideLeaveRequest(tenantId(authentication), id, request.status()));
    }

    /** The caller's own leave request history (SCHOOL_ADMIN or TEACHER, ownership-scoped by their own user id). */
    @GetMapping("/me/leave-requests")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<LeaveRequestResponse> ownLeaveRequests(Authentication authentication) {
        return staffService.ownLeaveRequests(tenantId(authentication), userId(authentication)).stream()
                .map(LeaveRequestResponse::from).toList();
    }

    private static UUID tenantId(Authentication authentication) {
        return UUID.fromString(jwt(authentication).getClaimAsString("tenant_id"));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(jwt(authentication).getSubject());
    }

    /**
     * The caller's own user id when they hold TEACHER (so the service restricts the
     * leave request to their own profile), or {@code null} when they hold SCHOOL_ADMIN
     * and may file on behalf of any staff member in the tenant.
     */
    private static UUID staffScope(Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> Roles.ROLE_SCHOOL_ADMIN.equals(a.getAuthority()));
        return isAdmin ? null : userId(authentication);
    }

    private static Jwt jwt(Authentication authentication) {
        return (Jwt) authentication.getPrincipal();
    }
}
