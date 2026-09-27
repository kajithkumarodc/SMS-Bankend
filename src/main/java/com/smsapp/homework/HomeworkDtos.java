package com.smsapp.homework;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the homework API. Entities are never exposed directly (plan section 7.1d). */
public final class HomeworkDtos {

    private HomeworkDtos() {
    }

    /** {@code assignedDate} defaults to the school's today when omitted. */
    record CreateHomeworkRequest(
            @NotNull UUID classId,
            @NotNull UUID sectionId,
            @NotNull UUID subjectId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10000) String description,
            LocalDate assignedDate,
            @NotNull LocalDate dueDate) {
    }

    /**
     * Only the fields a teacher can correct after posting. Class, section and subject
     * are fixed at creation: re-pointing homework at a different roster would silently
     * orphan every submission recorded against it.
     */
    record UpdateHomeworkRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10000) String description,
            @NotNull LocalDate dueDate) {
    }

    /**
     * {@code homeworkId} and {@code studentId} come from the path. {@code status} is
     * validated against {@link HomeworkSubmissionStatus} in the service.
     */
    record RecordSubmissionRequest(
            @NotBlank String status,
            @Size(max = 1000) String remarks) {
    }

    record HomeworkResponse(
            UUID id,
            UUID classId,
            UUID sectionId,
            UUID subjectId,
            String title,
            String description,
            LocalDate assignedDate,
            LocalDate dueDate,
            UUID createdByUserId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static HomeworkResponse from(Homework homework) {
            return new HomeworkResponse(homework.getId(), homework.getClassId(), homework.getSectionId(),
                    homework.getSubjectId(), homework.getTitle(), homework.getDescription(),
                    homework.getAssignedDate(), homework.getDueDate(), homework.getCreatedByUserId(),
                    homework.getCreatedAt(), homework.getUpdatedAt());
        }
    }

    /**
     * One row of the teacher's submission sheet: a student on the roster, plus their
     * recorded state. A student nobody has marked comes back as PENDING with null
     * timestamps rather than being omitted -- the sheet must show the whole roster.
     */
    record SubmissionRowResponse(
            UUID studentId,
            String studentName,
            String rollNumber,
            String status,
            OffsetDateTime submittedAt,
            String remarks,
            OffsetDateTime markedAt) {
    }

    /** The stored row itself, returned when one student's state is recorded. */
    record SubmissionResponse(
            UUID id,
            UUID homeworkId,
            UUID studentId,
            String status,
            OffsetDateTime submittedAt,
            String remarks,
            UUID markedByUserId,
            OffsetDateTime markedAt) {

        static SubmissionResponse from(HomeworkSubmission submission) {
            return new SubmissionResponse(submission.getId(), submission.getHomeworkId(), submission.getStudentId(),
                    submission.getStatus(), submission.getSubmittedAt(), submission.getRemarks(),
                    submission.getMarkedByUserId(), submission.getMarkedAt());
        }
    }

    /** The student/parent portal view: the homework plus that student's own state. */
    public record StudentHomeworkResponse(
            UUID id,
            UUID subjectId,
            String title,
            String description,
            LocalDate assignedDate,
            LocalDate dueDate,
            String status,
            OffsetDateTime submittedAt,
            String remarks) {
    }
}
