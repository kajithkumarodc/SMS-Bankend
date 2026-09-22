package com.smsapp.report;

import com.smsapp.report.ReportDtos.AttendanceTrendPoint;
import com.smsapp.report.ReportDtos.BalanceFeeEntry;
import com.smsapp.report.ReportDtos.ExamPerformancePoint;
import com.smsapp.report.ReportDtos.FeeCollectionReport;
import com.smsapp.report.ReportDtos.FeeCollectionTransactionsReport;
import com.smsapp.report.ReportDtos.DailyCollectionPoint;
import com.smsapp.user.Permissions;
import com.smsapp.user.Roles;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * School-level reporting (plan section 2, "Reporting and Analytics"). Every
 * endpoint is SCHOOL_ADMIN only -- a TEACHER, STUDENT or PARENT gets 403; a
 * teacher's/parent's narrower reports are a later slice.
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
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.attendanceTrend(from, to);
    }

    /** Average marks per exam for one class, oldest exam first. */
    @GetMapping("/academic-performance")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    List<ExamPerformancePoint> academicPerformance(@RequestParam UUID classId) {
        return reportService.academicPerformance(classId);
    }

    /** Total invoiced vs. collected, plus the overdue-invoice defaulter list. */
    @GetMapping("/fee-collection")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    FeeCollectionReport feeCollection() {
        return reportService.feeCollection();
    }

    /**
     * Balance Fees Report (plan Phase 5 part K): every student's totals across all their fee
     * invoices, optionally filtered to one class. New endpoint -- permission-based (FEE_VIEW),
     * reaching ACCOUNTANT as well as SCHOOL_ADMIN/SUPER_ADMIN.
     */
    @GetMapping("/fee-balances")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    List<BalanceFeeEntry> feeBalances(@RequestParam(required = false) UUID classId) {
        return reportService.balanceFees(classId);
    }

    /** Daily Collection (plan Phase 5 part K): real payments over an inclusive date range, grouped by day then method. */
    @GetMapping("/fee-daily-collection")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    List<DailyCollectionPoint> feeDailyCollection(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.dailyCollection(from, to);
    }

    /** Fee Collection Report (plan Phase 5 part K): filtered transaction list + summary totals. */
    @GetMapping("/fee-collection-transactions")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    FeeCollectionTransactionsReport feeCollectionTransactions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) UUID collectedByUserId,
            @RequestParam(required = false) UUID studentId,
            @RequestParam(required = false) UUID classId) {
        return reportService.feeCollectionTransactions(from, to, method, collectedByUserId, studentId, classId);
    }
}
