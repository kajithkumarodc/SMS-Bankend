package com.smsapp.staff;

import com.smsapp.staff.StaffAttendanceDtos.RosterRow;
import com.smsapp.staff.StaffAttendanceDtos.SaveRequest;
import com.smsapp.staff.StaffAttendanceDtos.SaveResponse;
import com.smsapp.staff.StaffDirectoryDtos.LookupOption;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Human Resource > Staff Attendance, gated by STAFF_ATTENDANCE_VIEW / STAFF_ATTENDANCE_EDIT (V45). */
@RestController
@RequestMapping("/api/v1/staff-attendance")
public class StaffAttendanceController {

    private final StaffAttendanceService service;

    public StaffAttendanceController(StaffAttendanceService service) {
        this.service = service;
    }

    /** The Role choices for the criteria. */
    @GetMapping("/roles")
    @PreAuthorize(Permissions.HAS_STAFF_ATTENDANCE_VIEW)
    List<LookupOption> roles() {
        return service.roles();
    }

    /** The active staff of one role with their saved marks for a day. */
    @GetMapping
    @PreAuthorize(Permissions.HAS_STAFF_ATTENDANCE_VIEW)
    List<RosterRow> roster(@RequestParam UUID roleId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.roster(roleId, date);
    }

    /** Saves the marks for one day. */
    @PutMapping
    @PreAuthorize(Permissions.HAS_STAFF_ATTENDANCE_EDIT)
    SaveResponse save(@Valid @RequestBody SaveRequest request, Authentication authentication) {
        UUID userId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        return new SaveResponse(service.save(request.date(), request.entries(), userId));
    }
}
