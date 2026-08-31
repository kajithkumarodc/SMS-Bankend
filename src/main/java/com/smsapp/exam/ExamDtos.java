package com.smsapp.exam;

import com.smsapp.exam.ExamMarkRepository.StudentExamResult;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the exams API. Entities are never exposed directly (plan section 7.1d). */
final class ExamDtos {

    private ExamDtos() {
    }

    record CreateExamRequest(
            @NotNull UUID classId,
            @NotNull UUID subjectId,
            @NotBlank @Size(max = 150) String name,
            @NotNull LocalDate examDate,
            @NotNull @DecimalMin(value = "0.01") BigDecimal maxMarks) {
    }

    /** {@code examId} comes from the path. Upper bound (<= exam's max marks) is checked in the service. */
    record RecordMarkRequest(
            @NotNull UUID studentId,
            @NotNull @DecimalMin(value = "0.0") BigDecimal marksObtained) {
    }

    record ExamResponse(
            UUID id,
            UUID classId,
            UUID subjectId,
            String name,
            LocalDate examDate,
            BigDecimal maxMarks,
            OffsetDateTime createdAt) {

        static ExamResponse from(Exam exam) {
            return new ExamResponse(exam.getId(), exam.getClassId(), exam.getSubjectId(), exam.getName(),
                    exam.getExamDate(), exam.getMaxMarks(), exam.getCreatedAt());
        }
    }

    record ExamMarkResponse(
            UUID id,
            UUID examId,
            UUID studentId,
            BigDecimal marksObtained,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static ExamMarkResponse from(ExamMark mark) {
            return new ExamMarkResponse(mark.getId(), mark.getExamId(), mark.getStudentId(), mark.getMarksObtained(),
                    mark.getCreatedAt(), mark.getUpdatedAt());
        }
    }

    record StudentExamResultResponse(
            UUID examId,
            String examName,
            LocalDate examDate,
            UUID subjectId,
            BigDecimal maxMarks,
            BigDecimal marksObtained) {

        static StudentExamResultResponse from(StudentExamResult result) {
            return new StudentExamResultResponse(result.getExamId(), result.getExamName(), result.getExamDate(),
                    result.getSubjectId(), result.getMaxMarks(), result.getMarksObtained());
        }
    }
}
