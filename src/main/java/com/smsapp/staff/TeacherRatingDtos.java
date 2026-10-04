package com.smsapp.staff;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the Teachers Rating API. */
final class TeacherRatingDtos {

    private TeacherRatingDtos() {
    }

    /** One rating on the Teachers Rating page. */
    record RatingRow(
            UUID id,
            UUID staffProfileId,
            String staffId,
            String staffName,
            int rating,
            String comment,
            /** PENDING or APPROVED. */
            String status,
            UUID studentId,
            String studentName,
            String studentAdmissionNumber,
            OffsetDateTime createdAt) {
    }

    /** A teacher's average over approved ratings; {@code average} is null when none is approved yet. */
    record RatingSummary(UUID staffProfileId, BigDecimal average, long count) {
    }

    /** Body for {@code POST /api/v1/me/teacher-ratings}: a student rates one teacher. */
    record SubmitRatingRequest(
            @NotNull UUID staffProfileId,
            @NotNull @Min(1) @Max(5) Integer rating,
            @Size(max = 1000) String comment) {
    }

    /** A teacher a student can rate, with that student's own rating of them if they gave one. */
    record RatableTeacher(
            UUID staffProfileId,
            String staffId,
            String name,
            String designation,
            String department,
            boolean hasPhoto,
            Integer myRating,
            String myComment,
            /** PENDING or APPROVED; null when the student has not rated this teacher. */
            String myStatus) {
    }
}
