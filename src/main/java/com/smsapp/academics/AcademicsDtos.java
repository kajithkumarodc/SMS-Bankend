package com.smsapp.academics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request/response payloads for the classes/sections API. Entities are never exposed directly. */
final class AcademicsDtos {

    private AcademicsDtos() {
    }

    record CreateClassRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 100) String name) {
    }

    /** {@code classId} comes from the path, not the body. */
    record CreateSectionRequest(
            @NotBlank @Size(max = 100) String name) {
    }

    record SectionResponse(UUID id, UUID classId, String name) {

        static SectionResponse from(Section section) {
            return new SectionResponse(section.getId(), section.getClassId(), section.getName());
        }
    }

    record ClassResponse(UUID id, UUID schoolId, String name, List<SectionResponse> sections) {
    }

    record CreateSubjectRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 100) String name) {
    }

    /** {@code classId} comes from the path; {@code subjectId} identifies the subject to assign. */
    record AssignSubjectRequest(
            @NotNull UUID subjectId) {
    }

    /**
     * Body for {@code PATCH /api/v1/classes/{classId}/subjects/{subjectId}/teacher}.
     * {@code teacherId == null} unassigns the current teacher.
     */
    record AssignTeacherRequest(UUID teacherId) {
    }

    record SubjectResponse(UUID id, UUID schoolId, String name) {

        static SubjectResponse from(Subject subject) {
            return new SubjectResponse(subject.getId(), subject.getSchoolId(), subject.getName());
        }
    }

    record CreateAcademicYearRequest(
            @NotBlank @Size(max = 20) String name,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate) {
    }

    record AcademicYearResponse(UUID id, String name, LocalDate startDate, LocalDate endDate, boolean current,
                                OffsetDateTime createdAt) {

        static AcademicYearResponse from(AcademicYear year) {
            return new AcademicYearResponse(year.getId(), year.getName(), year.getStartDate(), year.getEndDate(),
                    year.isCurrent(), year.getCreatedAt());
        }
    }

    /**
     * Body for {@code POST /api/v1/students/promote} -- bulk-moves students from one section to another.
     * {@code toClassId} is an optional coherency check (400 if {@code toSectionId} doesn't belong to it);
     * {@code targetAcademicYearId} is optional and defaults to whichever year is current.
     */
    record PromoteStudentsRequest(
            @NotNull UUID fromSectionId,
            @NotNull UUID toSectionId,
            UUID toClassId,
            UUID targetAcademicYearId,
            @NotEmpty Set<UUID> studentIds) {
    }

    record PromotionResultResponse(UUID studentId, String studentName, boolean promoted, String reason) {

        static PromotionResultResponse from(AcademicYearService.PromotionResult result) {
            return new PromotionResultResponse(result.studentId(), result.studentName(), result.promoted(), result.reason());
        }
    }

    record PromoteStudentsResponse(List<PromotionResultResponse> results, int promotedCount, int requestedCount) {
    }

    /** One row in the Promotion History screen (plan Phase 4 section A). */
    record PromotionHistoryEntry(
            UUID studentId,
            String studentName,
            UUID previousAcademicYearId,
            UUID previousClassId,
            UUID previousSectionId,
            UUID newAcademicYearId,
            UUID newClassId,
            UUID newSectionId,
            OffsetDateTime promotionDate,
            UUID performedByUserId) {
    }
}
