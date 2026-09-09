package com.smsapp.report;

import com.smsapp.fee.Invoice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only aggregate queries for school-level reporting (plan section 2,
 * "Reporting and Analytics"). Phase-1 reporting runs directly off the
 * operational tables -- no separate analytics store. Every query is explicitly
 * filtered by {@code tenant_id} on top of the database RLS policy, and the
 * string status literals ({@code 'PAID'}, ...) match the DB CHECK constraints
 * that are the source of truth for those sets.
 *
 * <p>Extends the bare {@link Repository} marker (not {@code JpaRepository}) so it
 * exposes only these query methods; the domain type is arbitrary since every
 * method names its own entities.
 */
public interface ReportRepository extends Repository<Invoice, UUID> {

    /** Attendance counts grouped by (day, status) across an inclusive date range, oldest day first. */
    @Query("select a.date as date, a.status as status, count(a) as count "
            + "from AttendanceRecord a "
            + "where a.tenantId = :tenantId and a.date between :from and :to "
            + "group by a.date, a.status "
            + "order by a.date")
    List<DailyStatusCount> attendanceCountsByDay(@Param("tenantId") UUID tenantId,
                                                 @Param("from") LocalDate from,
                                                 @Param("to") LocalDate to);

    /** Average recorded mark per exam for one class (only exams that have at least one mark). */
    @Query("select e.id as examId, e.name as examName, e.subjectId as subjectId, "
            + "e.examDate as examDate, e.maxMarks as maxMarks, "
            + "avg(m.marksObtained) as averageMarks, count(m) as studentsGraded "
            + "from ExamMark m, Exam e "
            + "where m.examId = e.id and m.tenantId = :tenantId and e.classId = :classId "
            + "group by e.id, e.name, e.subjectId, e.examDate, e.maxMarks "
            + "order by e.examDate")
    List<ExamAverage> averageMarksByExamForClass(@Param("tenantId") UUID tenantId,
                                                 @Param("classId") UUID classId);

    /** Total invoiced vs. total collected (PAID) for the tenant. Either side is null when there are no invoices. */
    @Query("select sum(i.amount) as invoiced, "
            + "sum(case when i.status = 'PAID' then i.amount else null end) as collected "
            + "from Invoice i where i.tenantId = :tenantId")
    CollectionTotals collectionTotals(@Param("tenantId") UUID tenantId);

    /** The defaulter list: still-PENDING invoices whose fee structure's due date has passed. */
    @Query("select i.id as invoiceId, i.studentId as studentId, f.name as feeStructureName, "
            + "i.amount as amount, f.dueDate as dueDate "
            + "from Invoice i, FeeStructure f "
            + "where i.feeStructureId = f.id and i.tenantId = :tenantId "
            + "and i.status = 'PENDING' and f.dueDate < :onDate "
            + "order by f.dueDate, i.id")
    List<OverdueRow> overdueInvoices(@Param("tenantId") UUID tenantId, @Param("onDate") LocalDate onDate);

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
}
