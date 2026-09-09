package com.smsapp.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response payloads for the school-level reports API. Entities are never exposed directly (plan section 7.1d). */
final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * One day of the attendance trend. {@code attendancePercentage} is
     * {@code (present + late) / total * 100} rounded to 2 dp (a late student did
     * attend); {@code 0} when nothing was marked that day.
     */
    record AttendanceTrendPoint(
            LocalDate date,
            long present,
            long absent,
            long late,
            long total,
            double attendancePercentage) {
    }

    /** One exam's average performance for a class. */
    record ExamPerformancePoint(
            UUID examId,
            String examName,
            UUID subjectId,
            LocalDate examDate,
            BigDecimal maxMarks,
            double averageMarks,
            long studentsGraded) {
    }

    /** Fee collection summary + the defaulter list. */
    record FeeCollectionReport(
            BigDecimal totalInvoiced,
            BigDecimal totalCollected,
            BigDecimal outstanding,
            List<OverdueInvoice> overdueInvoices) {
    }

    record OverdueInvoice(
            UUID invoiceId,
            UUID studentId,
            String feeStructureName,
            BigDecimal amount,
            LocalDate dueDate) {
    }
}
