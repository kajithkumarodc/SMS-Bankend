package com.smsapp.exam;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExamMarkRepository extends JpaRepository<ExamMark, UUID> {

    /** The gradebook: every recorded mark for one exam. */
    List<ExamMark> findByExamIdOrderByCreatedAt(UUID examId);

    Optional<ExamMark> findByExamIdAndStudentId(UUID examId, UUID studentId);

    /** One student's results across every exam (joins exam context in). */
    @Query("select m.marksObtained as marksObtained, e.id as examId, e.name as examName, "
            + "e.examDate as examDate, e.subjectId as subjectId, e.maxMarks as maxMarks "
            + "from ExamMark m, Exam e "
            + "where m.examId = e.id and m.studentId = :studentId "
            + "order by e.examDate desc")
    List<StudentExamResult> findResultsForStudent(@Param("studentId") UUID studentId);

    interface StudentExamResult {
        BigDecimal getMarksObtained();

        UUID getExamId();

        String getExamName();

        LocalDate getExamDate();

        UUID getSubjectId();

        BigDecimal getMaxMarks();
    }
}
