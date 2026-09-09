package com.smsapp.report;

import com.smsapp.attendance.AttendanceStatus;
import com.smsapp.report.ReportDtos.AttendanceTrendPoint;
import com.smsapp.report.ReportDtos.ExamPerformancePoint;
import com.smsapp.report.ReportDtos.FeeCollectionReport;
import com.smsapp.report.ReportDtos.OverdueInvoice;
import com.smsapp.report.ReportRepository.CollectionTotals;
import com.smsapp.report.ReportRepository.DailyStatusCount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * School-level reporting off the operational tables (plan section 2). Every query
 * is tenant-scoped in {@link ReportRepository}; these methods only reshape the
 * aggregate rows into the response DTOs.
 */
@Service
public class ReportService {

    private final ReportRepository reportRepository;

    public ReportService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** Daily present/absent/late tallies + attendance % over an inclusive {@code [from, to]} range. */
    @Transactional(readOnly = true)
    public List<AttendanceTrendPoint> attendanceTrend(UUID tenantId, LocalDate from, LocalDate to) {
        // pivot the (date, status, count) rows into one entry per day; the query
        // orders by date so a LinkedHashMap keeps the days in order.
        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        for (DailyStatusCount row : reportRepository.attendanceCountsByDay(tenantId, from, to)) {
            long[] counts = perDay.computeIfAbsent(row.getDate(), d -> new long[3]);
            switch (row.getStatus()) {
                case AttendanceStatus.PRESENT -> counts[0] += row.getCount();
                case AttendanceStatus.ABSENT -> counts[1] += row.getCount();
                case AttendanceStatus.LATE -> counts[2] += row.getCount();
                default -> { /* unknown status: ignore */ }
            }
        }

        List<AttendanceTrendPoint> trend = new ArrayList<>(perDay.size());
        perDay.forEach((date, counts) -> {
            long present = counts[0];
            long absent = counts[1];
            long late = counts[2];
            long total = present + absent + late;
            double percentage = total == 0
                    ? 0d
                    : Math.round((present + late) * 10_000d / total) / 100d;
            trend.add(new AttendanceTrendPoint(date, present, absent, late, total, percentage));
        });
        return trend;
    }

    /** Average recorded mark per exam for a class -- highlights the exams/subjects a class is struggling with. */
    @Transactional(readOnly = true)
    public List<ExamPerformancePoint> academicPerformance(UUID tenantId, UUID classId) {
        return reportRepository.averageMarksByExamForClass(tenantId, classId).stream()
                .map(row -> new ExamPerformancePoint(
                        row.getExamId(),
                        row.getExamName(),
                        row.getSubjectId(),
                        row.getExamDate(),
                        row.getMaxMarks(),
                        row.getAverageMarks() == null
                                ? 0d
                                : Math.round(row.getAverageMarks() * 100d) / 100d,
                        row.getStudentsGraded()))
                .toList();
    }

    /** Total invoiced vs. collected for the tenant, plus the overdue-invoice defaulter list. */
    @Transactional(readOnly = true)
    public FeeCollectionReport feeCollection(UUID tenantId) {
        CollectionTotals totals = reportRepository.collectionTotals(tenantId);
        BigDecimal invoiced = zeroIfNull(totals == null ? null : totals.getInvoiced());
        BigDecimal collected = zeroIfNull(totals == null ? null : totals.getCollected());

        List<OverdueInvoice> overdue = reportRepository.overdueInvoices(tenantId, LocalDate.now()).stream()
                .map(row -> new OverdueInvoice(row.getInvoiceId(), row.getStudentId(),
                        row.getFeeStructureName(), row.getAmount(), row.getDueDate()))
                .toList();

        return new FeeCollectionReport(invoiced, collected, invoiced.subtract(collected), overdue);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
