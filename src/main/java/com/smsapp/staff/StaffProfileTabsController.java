package com.smsapp.staff;

import com.smsapp.staff.LeaveManagementService.LeaveActor;
import com.smsapp.staff.LeaveManagementService.StaffLeaves;
import com.smsapp.staff.StaffProfileTabsService.AttendanceSummary;
import com.smsapp.staff.StaffProfileTabsService.PayrollSummary;
import com.smsapp.user.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The tabs and actions of a staff member's profile page: Payroll, Leaves and Attendance (read-only views of the
 * Payroll, Leave and Staff Attendance modules) plus disabling the account and issuing a new login password.
 */
@RestController
@RequestMapping("/api/v1/staff-members/{id}")
public class StaffProfileTabsController {

    record StatusRequest(boolean active) {
    }

    record PasswordResponse(String temporaryPassword) {
    }

    private final StaffProfileTabsService tabs;
    private final LeaveManagementService leaves;
    private final StaffDirectoryService directory;

    public StaffProfileTabsController(StaffProfileTabsService tabs, LeaveManagementService leaves,
                                      StaffDirectoryService directory) {
        this.tabs = tabs;
        this.leaves = leaves;
        this.directory = directory;
    }

    @GetMapping("/payroll")
    @PreAuthorize(Permissions.HAS_STAFF_VIEW + " and " + Permissions.HAS_PAYROLL_VIEW)
    PayrollSummary payroll(@PathVariable UUID id) {
        return tabs.payroll(id);
    }

    @GetMapping("/leaves")
    @PreAuthorize(Permissions.HAS_STAFF_VIEW + " and " + Permissions.HAS_LEAVE_VIEW)
    StaffLeaves leaves(@PathVariable UUID id, @RequestParam(required = false) Integer year,
                       Authentication authentication) {
        return leaves.forStaff(id, year == null ? LocalDate.now().getYear() : year, actor(authentication));
    }

    @GetMapping("/attendance")
    @PreAuthorize(Permissions.HAS_STAFF_VIEW + " and " + Permissions.HAS_STAFF_ATTENDANCE_VIEW)
    AttendanceSummary attendance(@PathVariable UUID id, @RequestParam(required = false) Integer year) {
        return tabs.attendance(id, year == null ? LocalDate.now().getYear() : year);
    }

    /** Disables ({@code active: false}) or re-enables the staff member and their login. */
    @PatchMapping("/status")
    @PreAuthorize(Permissions.HAS_STAFF_EDIT)
    StaffDirectoryDtos.StaffMemberResponse status(@PathVariable UUID id, @RequestBody StatusRequest request) {
        return directory.toResponse(directory.setActive(id, request.active()));
    }

    /** A new temporary password for the staff member's login, shown once. */
    @PostMapping("/reset-password")
    @PreAuthorize(Permissions.HAS_STAFF_EDIT)
    PasswordResponse resetPassword(@PathVariable UUID id) {
        return new PasswordResponse(directory.resetPassword(id));
    }

    private static LeaveActor actor(Authentication authentication) {
        UUID userId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        Set<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring("ROLE_".length())).collect(Collectors.toSet());
        return new LeaveActor(userId, roles);
    }
}
