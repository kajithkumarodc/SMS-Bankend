package com.smsapp.academics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
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

    record SubjectResponse(UUID id, UUID schoolId, String name) {

        static SubjectResponse from(Subject subject) {
            return new SubjectResponse(subject.getId(), subject.getSchoolId(), subject.getName());
        }
    }
}
