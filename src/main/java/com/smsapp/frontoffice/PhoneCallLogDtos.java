package com.smsapp.frontoffice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the Phone Call Log API. */
final class PhoneCallLogDtos {

    private PhoneCallLogDtos() {
    }

    /** Body for {@code POST /api/v1/phone-calls} and {@code PUT /api/v1/phone-calls/{id}} -- the Add Phone Call Log form. */
    record PhoneCallRequest(
            @Size(max = 200) String name,
            @NotBlank @Size(max = 30)
            @Pattern(regexp = EnquiryDtos.PHONE_PATTERN, message = "must be a valid phone number") String phone,
            @NotNull LocalDate callDate,
            @Size(max = 2000) String description,
            /** Must not be before {@code callDate}. */
            LocalDate nextFollowUpDate,
            @Size(max = 50) String callDuration,
            @Size(max = 2000) String note,
            /** INCOMING or OUTGOING. */
            @NotBlank String callType) {
    }

    record PhoneCallResponse(
            UUID id,
            String name,
            String phone,
            LocalDate callDate,
            String description,
            LocalDate nextFollowUpDate,
            String callDuration,
            String note,
            String callType,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static PhoneCallResponse from(PhoneCallLog c) {
            return new PhoneCallResponse(c.getId(), c.getName(), c.getPhone(), c.getCallDate(), c.getDescription(),
                    c.getNextFollowUpDate(), c.getCallDuration(), c.getNote(), c.getCallType(), c.getCreatedAt(),
                    c.getUpdatedAt());
        }
    }
}
