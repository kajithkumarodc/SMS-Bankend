package com.smsapp.report;

import com.smsapp.report.ReportDtos.AttendanceTrendPoint;
import com.smsapp.report.ReportDtos.ExamPerformancePoint;
import com.smsapp.report.ReportDtos.FeeCollectionReport;
import com.smsapp.user.Roles;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * School-level reporting (plan section 2, "Reporting and Analytics"). Every
 * endpoint is SCHOOL_ADMIN only and tenant-scoped -- a TEACHER, STUDENT or PARENT
 * gets 403; a teacher's/parent's narrower reports are a later slice.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /** Daily attendance percentage (present/absent/late counts per day) over an inclusive date range. */
    @GetMapping("/attendance-trend")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    List<AttendanceTrendPoint> attendanceTrend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        return reportService.attendanceTrend(tenantId(authentication), from, to);
    }

    /** Average marks per exam for one class, oldest exam first. */
    @GetMapping("/academic-performance")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    List<ExamPerformancePoint> academicPerformance(@RequestParam UUID classId, Authentication authentication) {
        return reportService.academicPerformance(tenantId(authentication), classId);
    }

    /** Total invoiced vs. collected for the tenant, plus the overdue-invoice defaulter list. */
    @GetMapping("/fee-collection")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    FeeCollectionReport feeCollection(Authentication authentication) {
        return reportService.feeCollection(tenantId(authentication));
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
