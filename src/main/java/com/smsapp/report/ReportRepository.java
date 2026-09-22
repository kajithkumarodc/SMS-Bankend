package com.smsapp.report;

import com.smsapp.fee.Invoice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregate queries for school-level reporting (plan section 2,
 * "Reporting and Analytics"). Phase-1 reporting runs directly off the
 * operational tables -- no separate analytics store. The string status literals
 * ({@code 'PAID'}, ...) match the DB CHECK constraints that are the source of
 * truth for those sets.
 *
 * <p>Extends the bare {@link Repository} marker (not {@code JpaRepository}) so it
 * exposes only these query methods; the domain type is arbitrary since every
 * method names its own entities.
 */
public interface ReportRepository extends Repository<Invoice, UUID> {

    /** Attendance counts grouped by (day, status) across an inclusive date range, oldest day first. */
    @Query("select a.date as date, a.status as status, count(a) as count "
            + "from AttendanceRecord a "
            + "where a.date between :from and :to "
            + "group by a.date, a.status "
            + "order by a.date")
    List<DailyStatusCount> attendanceCountsByDay(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Average recorded mark per exam for one class (only exams that have at least one mark). */
    @Query("select e.id as examId, e.name as examName, e.subjectId as subjectId, "
            + "e.examDate as examDate, e.maxMarks as maxMarks, "
            + "avg(m.marksObtained) as averageMarks, count(m) as studentsGraded "
            + "from ExamMark m, Exam e "
            + "where m.examId = e.id and e.classId = :classId "
            + "group by e.id, e.name, e.subjectId, e.examDate, e.maxMarks "
            + "order by e.examDate")
    List<ExamAverage> averageMarksByExamForClass(@Param("classId") UUID classId);

    /**
     * Total invoiced (gross, pre-discount) vs. total actually collected -- the real sum of
     * non-reversed {@link com.smsapp.fee.FeePayment} rows (plan Phase 5 part H: collection must be
     * computed from the payment ledger, not inferred from an invoice-level PAID/PENDING flag, so a
     * partially-paid invoice's real collected amount is still counted). Either side is null when
     * there is nothing to sum.
     */
    @Query("select sum(i.amount) as invoiced, "
            + "(select sum(case when p.type = 'PAYMENT' then p.amount else -p.amount end) from FeePayment p) as collected "
            + "from Invoice i")
    CollectionTotals collectionTotals();

    /** The defaulter list: invoices with an outstanding balance whose fee structure's due date has passed. */
    @Query("select i.id as invoiceId, i.studentId as studentId, f.name as feeStructureName, "
            + "(i.netAmount - i.paidAmount) as amount, f.dueDate as dueDate "
            + "from Invoice i, FeeStructure f "
            + "where i.feeStructureId = f.id "
            + "and (i.netAmount - i.paidAmount) > 0 and f.dueDate < :onDate "
            + "order by f.dueDate, i.id")
    List<OverdueRow> overdueInvoices(@Param("onDate") LocalDate onDate);

    /**
     * Balance Fees Report (plan Phase 5 part K): every student with at least one invoice,
     * aggregated across all of them. Optional class filter (student's current section's class).
     */
    @Query("select s.id as studentId, s.fullName as fullName, s.admissionNumber as admissionNumber, "
            + "s.sectionId as sectionId, "
            + "sum(i.netAmount) as totalPayable, sum(i.paidAmount) as totalPaid, "
            + "sum(i.netAmount - i.paidAmount) as totalBalance "
            + "from Invoice i, com.smsapp.student.Student s "
            + "where i.studentId = s.id "
            + "and (:classId is null or s.sectionId in "
            + "  (select sec.id from com.smsapp.academics.Section sec where sec.classId = :classId)) "
            + "group by s.id, s.fullName, s.admissionNumber, s.sectionId "
            + "order by s.fullName")
    List<BalanceFeeRow> balanceFees(@Param("classId") UUID classId);

    /**
     * Every real payment/reversal in an inclusive {@code [from, to)} instant range -- raw rows, so
     * {@code ReportService} can pivot them per day the same way it already pivots
     * {@link #attendanceCountsByDay} (grouping in Java avoids a DB-specific date-truncation
     * function). Used for both Daily Collection and dashboard trend widgets.
     */
    @Query("select p.paidAt as paidAt, p.method as method, "
            + "case when p.type = 'PAYMENT' then p.amount else -p.amount end as signedAmount "
            + "from FeePayment p "
            + "where p.paidAt >= :from and p.paidAt < :to "
            + "order by p.paidAt")
    List<PaymentInstantRow> paymentsBetween(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /**
     * Fee Collection Report transaction list (plan Phase 5 part K): every payment/reversal in an
     * inclusive {@code [from, to)} instant range, with the optional method/collector/student/class
     * filters a school needs to reconcile a day's collection. Every filter parameter may be
     * {@code null} to not filter on it.
     */
    @Query("select p.id as paymentId, p.paidAt as paidAt, p.type as type, p.amount as amount, "
            + "p.method as method, p.receiptNumber as receiptNumber, s.id as studentId, "
            + "s.fullName as studentName, s.admissionNumber as admissionNumber, f.name as feeStructureName, "
            + "p.collectedByUserId as collectedByUserId "
            + "from FeePayment p, Invoice i, FeeStructure f, com.smsapp.student.Student s "
            + "where p.invoiceId = i.id and i.feeStructureId = f.id and i.studentId = s.id "
            + "and p.paidAt >= :from and p.paidAt < :to "
            + "and (:method is null or p.method = :method) "
            + "and (:collectedByUserId is null or p.collectedByUserId = :collectedByUserId) "
            + "and (:studentId is null or s.id = :studentId) "
            + "and (:classId is null or s.sectionId in "
            + "  (select sec.id from com.smsapp.academics.Section sec where sec.classId = :classId)) "
            + "order by p.paidAt desc")
    List<FeeTransactionRow> feeCollectionTransactions(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("method") String method, @Param("collectedByUserId") UUID collectedByUserId,
            @Param("studentId") UUID studentId, @Param("classId") UUID classId);

    interface DailyStatusCount {
        LocalDate getDate();

        String getStatus();

        long getCount();
    }

    interface ExamAverage {
        UUID getExamId();

        String getExamName();

        UUID getSubjectId();

        LocalDate getExamDate();

        BigDecimal getMaxMarks();

        Double getAverageMarks();

        long getStudentsGraded();
    }

    interface CollectionTotals {
        BigDecimal getInvoiced();

        BigDecimal getCollected();
    }

    interface OverdueRow {
        UUID getInvoiceId();

        UUID getStudentId();

        String getFeeStructureName();

        BigDecimal getAmount();

        LocalDate getDueDate();
    }

    interface BalanceFeeRow {
        UUID getStudentId();

        String getFullName();

        String getAdmissionNumber();

        UUID getSectionId();

        BigDecimal getTotalPayable();

        BigDecimal getTotalPaid();

        BigDecimal getTotalBalance();
    }

    interface PaymentInstantRow {
        OffsetDateTime getPaidAt();

        String getMethod();

        BigDecimal getSignedAmount();
    }

    interface FeeTransactionRow {
        UUID getPaymentId();

        OffsetDateTime getPaidAt();

        String getType();

        BigDecimal getAmount();

        String getMethod();

        String getReceiptNumber();

        UUID getStudentId();

        String getStudentName();

        String getAdmissionNumber();

        String getFeeStructureName();

        UUID getCollectedByUserId();
    }
}
