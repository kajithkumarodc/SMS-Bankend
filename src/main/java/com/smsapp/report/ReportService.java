package com.smsapp.report;

import com.smsapp.attendance.AttendanceStatus;
import com.smsapp.report.ReportDtos.AttendanceTrendPoint;
import com.smsapp.report.ReportDtos.BalanceFeeEntry;
import com.smsapp.report.ReportDtos.DailyCollectionPoint;
import com.smsapp.report.ReportDtos.ExamPerformancePoint;
import com.smsapp.report.ReportDtos.FeeCollectionReport;
import com.smsapp.report.ReportDtos.FeeCollectionTransactionsReport;
import com.smsapp.report.ReportDtos.FeeTransaction;
import com.smsapp.report.ReportDtos.OverdueInvoice;
import com.smsapp.report.ReportRepository.CollectionTotals;
import com.smsapp.report.ReportRepository.DailyStatusCount;
import com.smsapp.report.ReportRepository.PaymentInstantRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * School-level reporting off the operational tables (plan section 2). These
 * methods only reshape the aggregate rows from {@link ReportRepository} into
 * the response DTOs.
 */
@Service
public class ReportService {

    private final ReportRepository reportRepository;

    public ReportService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    /** Daily present/absent/late tallies + attendance % over an inclusive {@code [from, to]} range. */
    @Transactional(readOnly = true)
    public List<AttendanceTrendPoint> attendanceTrend(LocalDate from, LocalDate to) {
        // pivot the (date, status, count) rows into one entry per day; the query
        // orders by date so a LinkedHashMap keeps the days in order.
        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        for (DailyStatusCount row : reportRepository.attendanceCountsByDay(from, to)) {
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
    public List<ExamPerformancePoint> academicPerformance(UUID classId) {
        return reportRepository.averageMarksByExamForClass(classId).stream()
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

    /** Total invoiced vs. collected, plus the overdue-invoice defaulter list. */
    @Transactional(readOnly = true)
    public FeeCollectionReport feeCollection() {
        CollectionTotals totals = reportRepository.collectionTotals();
        BigDecimal invoiced = zeroIfNull(totals == null ? null : totals.getInvoiced());
        BigDecimal collected = zeroIfNull(totals == null ? null : totals.getCollected());

        List<OverdueInvoice> overdue = reportRepository.overdueInvoices(LocalDate.now()).stream()
                .map(row -> new OverdueInvoice(row.getInvoiceId(), row.getStudentId(),
                        row.getFeeStructureName(), row.getAmount(), row.getDueDate()))
                .toList();

        return new FeeCollectionReport(invoiced, collected, invoiced.subtract(collected), overdue);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // --- Phase 5: Fee Reports (plan part K) ------------------------------

    /** Balance Fees Report: every student's totals across all their fee invoices, optionally filtered to one class. */
    @Transactional(readOnly = true)
    public List<BalanceFeeEntry> balanceFees(UUID classId) {
        return reportRepository.balanceFees(classId).stream()
                .map(row -> new BalanceFeeEntry(row.getStudentId(), row.getFullName(), row.getAdmissionNumber(),
                        row.getSectionId(), row.getTotalPayable(), row.getTotalPaid(), row.getTotalBalance()))
                .toList();
    }

    /** Daily Collection: real payments over an inclusive {@code [from, to]} date range, grouped by day then method. */
    @Transactional(readOnly = true)
    public List<DailyCollectionPoint> dailyCollection(LocalDate from, LocalDate to) {
        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        Map<LocalDate, Map<String, BigDecimal>> perDay = new TreeMap<>();
        for (PaymentInstantRow row : reportRepository.paymentsBetween(start, end)) {
            LocalDate day = row.getPaidAt().atZoneSameInstant(zone).toLocalDate();
            perDay.computeIfAbsent(day, d -> new LinkedHashMap<>())
                    .merge(row.getMethod(), row.getSignedAmount(), BigDecimal::add);
        }

        List<DailyCollectionPoint> points = new ArrayList<>(perDay.size());
        perDay.forEach((day, byMethod) -> {
            BigDecimal total = byMethod.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            points.add(new DailyCollectionPoint(day, byMethod, total));
        });
        return points;
    }

    /**
     * Fee Collection Report: every payment/reversal over an inclusive {@code [from, to]} date range,
     * with optional method/collector/student/class filters, plus summary totals.
     */
    @Transactional(readOnly = true)
    public FeeCollectionTransactionsReport feeCollectionTransactions(LocalDate from, LocalDate to, String method,
            UUID collectedByUserId, UUID studentId, UUID classId) {
        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        List<FeeTransaction> transactions = reportRepository
                .feeCollectionTransactions(start, end, method, collectedByUserId, studentId, classId).stream()
                .map(row -> new FeeTransaction(row.getPaymentId(), row.getPaidAt(), row.getType(), row.getAmount(),
                        row.getMethod(), row.getReceiptNumber(), row.getStudentId(), row.getStudentName(),
                        row.getAdmissionNumber(), row.getFeeStructureName(), row.getCollectedByUserId()))
                .toList();

        BigDecimal totalCollected = transactions.stream()
                .map(t -> com.smsapp.fee.PaymentType.REVERSAL.equals(t.type()) ? t.amount().negate() : t.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FeeCollectionTransactionsReport(transactions, transactions.size(), totalCollected);
    }
}
