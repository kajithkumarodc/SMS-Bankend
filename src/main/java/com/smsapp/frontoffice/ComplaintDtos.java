package com.smsapp.frontoffice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the Complaints API. */
final class ComplaintDtos {

    private ComplaintDtos() {
    }

    /**
     * Body for {@code POST /api/v1/complaints} and {@code PUT /api/v1/complaints/{id}} -- the Add Complain form.
     * There's no complaint number: the database assigns it. The document is uploaded separately.
     */
    record ComplaintRequest(
            UUID complaintTypeId,
            UUID sourceId,
            @NotBlank @Size(max = 200) String complainBy,
            @Size(max = 30)
            @Pattern(regexp = EnquiryDtos.PHONE_PATTERN, message = "must be a valid phone number") String phone,
            @NotNull LocalDate complaintDate,
            @Size(max = 2000) String description,
            @Size(max = 500) String actionTaken,
            @Size(max = 200) String assigned,
            @Size(max = 2000) String note) {
    }

    record AttachmentInfo(String fileName, String contentType, long sizeBytes) {
    }

    record ComplaintResponse(
            UUID id,
            long complaintNo,
            UUID complaintTypeId,
            String complaintTypeName,
            UUID sourceId,
            String sourceName,
            String complainBy,
            String phone,
            LocalDate complaintDate,
            String description,
            String actionTaken,
            String assigned,
            String note,
            /** Null when no document is attached. */
            AttachmentInfo attachment,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static ComplaintResponse from(Complaint c, String complaintTypeName, String sourceName) {
            AttachmentInfo attachment = c.hasAttachment()
                    ? new AttachmentInfo(c.getAttachmentOriginalFilename(), c.getAttachmentContentType(),
                    c.getAttachmentSizeBytes() == null ? 0 : c.getAttachmentSizeBytes())
                    : null;
            return new ComplaintResponse(c.getId(), c.getComplaintNo(), c.getComplaintTypeId(), complaintTypeName,
                    c.getSourceId(), sourceName, c.getComplainBy(), c.getPhone(), c.getComplaintDate(),
                    c.getDescription(), c.getActionTaken(), c.getAssigned(), c.getNote(), attachment, c.getCreatedAt(),
                    c.getUpdatedAt());
        }
    }

    record CreateComplaintTypeRequest(@NotBlank @Size(max = 100) String name) {
    }

    record ComplaintTypeResponse(UUID id, String name, boolean active) {

        static ComplaintTypeResponse from(ComplaintType type) {
            return new ComplaintTypeResponse(type.getId(), type.getName(), type.isActive());
        }
    }
}
