package com.smsapp.attendance;

import com.smsapp.attendance.AttendanceDtos.AttendanceResponse;
import com.smsapp.attendance.AttendanceDtos.MarkAttendanceRequest;
import com.smsapp.attendance.AttendanceService.MarkResult;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    /**
     * Marks attendance for a student on a date. Both SCHOOL_ADMIN and TEACHER may do
     * this -- attendance is the teacher's daily job (plan section 2), unlike student
     * enrolment which is admin-only. Re-marking the same student+date updates the
     * existing record (200) rather than failing; a first mark returns 201.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('" + Roles.SCHOOL_ADMIN + "', '" + Roles.TEACHER + "')")
    public ResponseEntity<AttendanceResponse> mark(@Valid @RequestBody MarkAttendanceRequest request,
                                                   Authentication authentication) {
        MarkResult result = attendanceService.mark(tenantId(authentication), userId(authentication), request);
        AttendanceResponse body = AttendanceResponse.from(result.record());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    /**
     * Attendance records for {@code date} in the caller's tenant. With {@code sectionId}
     * the result is scoped to that section (the marking roster's current state -- may be
     * partial or empty); 404 if the section is not in the caller's tenant. Without it,
     * the whole tenant's roster for the day, paginated.
     */
    @GetMapping
    PagedModel<AttendanceResponse> list(
            @RequestParam(required = false) UUID sectionId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @PageableDefault(size = 50) Pageable pageable,
            Authentication authentication) {
        UUID tenantId = tenantId(authentication);
        if (sectionId != null) {
            List<AttendanceResponse> records = attendanceService.listForSectionOnDate(tenantId, sectionId, date)
                    .stream().map(AttendanceResponse::from).toList();
            return new PagedModel<>(new PageImpl<>(records));
        }
        return new PagedModel<>(attendanceService.listByDate(tenantId, date, pageable)
                .map(AttendanceResponse::from));
    }

    /** One student's attendance history (most recent first). 404 if the student is not in the caller's tenant. */
    @GetMapping("/student/{studentId}")
    PagedModel<AttendanceResponse> studentHistory(@PathVariable UUID studentId,
                                                  @PageableDefault(size = 50) Pageable pageable,
                                                  Authentication authentication) {
        return new PagedModel<>(attendanceService.studentHistory(tenantId(authentication), studentId, pageable)
                .map(AttendanceResponse::from));
    }

    private static UUID tenantId(Authentication authentication) {
        return UUID.fromString(jwt(authentication).getClaimAsString("tenant_id"));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(jwt(authentication).getSubject());
    }

    private static Jwt jwt(Authentication authentication) {
        return (Jwt) authentication.getPrincipal();
    }
}
