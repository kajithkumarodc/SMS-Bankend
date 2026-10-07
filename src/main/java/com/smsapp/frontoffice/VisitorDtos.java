package com.smsapp.frontoffice;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the Visitor Book API. Entities are never exposed directly. */
final class VisitorDtos {

    private VisitorDtos() {
    }

    /**
     * Body for {@code POST /api/v1/visitors} and {@code PUT /api/v1/visitors/{id}} -- the Add Visitor form.
     * {@code meetingWithType} picks which of {@code staffProfileId}/{@code studentId} is used; the other is ignored.
     * The attachment is uploaded separately ({@code PUT /api/v1/visitors/{id}/attachment}).
     */
    record VisitorRequest(
            @NotNull UUID purposeId,
            @NotBlank String meetingWithType,
            UUID staffProfileId,
            UUID studentId,
            @NotBlank @Size(max = 200) String visitorName,
            @Size(max = 30)
            @Pattern(regexp = EnquiryDtos.PHONE_PATTERN, message = "must be a valid phone number") String phone,
            @Size(max = 100) String idCard,
            @Min(1) @Max(999) Integer numberOfPersons,
            @NotNull LocalDate visitDate,
            LocalTime inTime,
            /** Must not be before {@code inTime} when both are given. */
            LocalTime outTime,
            @Size(max = 2000) String note) {
    }

    record AttachmentInfo(String fileName, String contentType, long sizeBytes) {
    }

    record VisitorResponse(
            UUID id,
            UUID purposeId,
            String purposeName,
            String meetingWithType,
            UUID staffProfileId,
            UUID studentId,
            /** The staff member's or student's name, e.g. "Joe Black". */
            String meetingWithName,
            /** Employee code or admission number, e.g. "9000". */
            String meetingWithCode,
            String visitorName,
            String phone,
            String idCard,
            Integer numberOfPersons,
            LocalDate visitDate,
            LocalTime inTime,
            LocalTime outTime,
            String note,
            /** Null when no document is attached. */
            AttachmentInfo attachment,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static VisitorResponse from(Visitor v, String purposeName, MeetingPerson person) {
            AttachmentInfo attachment = v.hasAttachment()
                    ? new AttachmentInfo(v.getAttachmentOriginalFilename(), v.getAttachmentContentType(),
                    v.getAttachmentSizeBytes() == null ? 0 : v.getAttachmentSizeBytes())
                    : null;
            return new VisitorResponse(v.getId(), v.getPurposeId(), purposeName, v.getMeetingWithType(),
                    v.getStaffProfileId(), v.getStudentId(), person == null ? null : person.name(),
                    person == null ? null : person.code(), v.getVisitorName(), v.getPhone(), v.getIdCard(),
                    v.getNumberOfPersons() == null ? null : v.getNumberOfPersons().intValue(), v.getVisitDate(),
                    v.getInTime(), v.getOutTime(), v.getNote(), attachment, v.getCreatedAt(), v.getUpdatedAt());
        }
    }

    /** A staff member or student a visitor can meet: display name plus employee code / admission number. */
    record MeetingPerson(UUID id, String type, String name, String code) {
    }

    record CreatePurposeRequest(@NotBlank @Size(max = 100) String name) {
    }

    record PurposeResponse(UUID id, String name, boolean active) {

        static PurposeResponse from(FrontOfficePurpose purpose) {
            return new PurposeResponse(purpose.getId(), purpose.getName(), purpose.isActive());
        }
    }
}
