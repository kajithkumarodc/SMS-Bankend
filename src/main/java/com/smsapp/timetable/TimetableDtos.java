package com.smsapp.timetable;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for Academics > Subjects, Subject Group and Class Timetable. */
final class TimetableDtos {

    private TimetableDtos() {
    }

    record SubjectRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 30) String code,
            @NotBlank @Pattern(regexp = "(?i)THEORY|PRACTICAL", message = "must be Theory or Practical") String type) {
    }

    record SubjectResponse(UUID id, String name, String code, String type) {
    }

    record SubjectGroupRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull UUID classId,
            @NotEmpty @Size(max = 50) List<@NotNull UUID> sectionIds,
            @NotEmpty @Size(max = 100) List<@NotNull UUID> subjectIds,
            @Size(max = 500) String description) {
    }

    record SectionRef(UUID id, String name, boolean isDefault) {
    }

    record SubjectRef(UUID id, String name, String code) {
    }

    record SubjectGroupResponse(UUID id, String name, String description, UUID classId, String className, List<SectionRef> sections,
                                List<SubjectRef> subjects) {
    }

    /** One period on the Create Timetable page. {@code dayOfWeek}: 1 = Monday ... 7 = Sunday. */
    record PeriodRequest(
            @NotNull @Min(1) @Max(7) Integer dayOfWeek,
            @NotNull UUID subjectId,
            @NotNull LocalTime timeFrom,
            @NotNull LocalTime timeTo,
            UUID staffProfileId,
            @Size(max = 30) String roomNo) {
    }

    /** Body for {@code PUT /api/v1/academics/timetable}: replaces a section's periods for one subject group. */
    record TimetableSaveRequest(
            @NotNull UUID sectionId,
            @NotNull UUID subjectGroupId,
            @NotNull @Size(max = 200) List<@Valid PeriodRequest> periods) {
    }

    record PeriodResponse(
            UUID id,
            UUID sectionId,
            UUID subjectGroupId,
            int dayOfWeek,
            UUID subjectId,
            String subjectName,
            String subjectCode,
            LocalTime timeFrom,
            LocalTime timeTo,
            UUID staffProfileId,
            String staffName,
            String staffCode,
            String roomNo) {
    }
}
