package com.smsapp.frontoffice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response payloads for the Postal Dispatch API. */
final class PostalDispatchDtos {

    private PostalDispatchDtos() {
    }

    /**
     * Body for {@code POST /api/v1/postal-dispatches} and {@code PUT /api/v1/postal-dispatches/{id}}. There is
     * deliberately no reference number: the server issues it on create and it can never be changed.
     */
    record DispatchRequest(
            @NotBlank @Size(max = 200) String toTitle,
            @Size(max = 200) String fromTitle,
            @Size(max = 1000) String address,
            @Size(max = 2000) String note,
            @NotNull LocalDate dispatchDate) {
    }

    record DocumentResponse(UUID id, String fileName, String contentType, long sizeBytes, OffsetDateTime uploadedAt) {

        static DocumentResponse from(PostalDispatchDocument d) {
            return new DocumentResponse(d.getId(), d.getOriginalFilename(), d.getContentType(), d.getSizeBytes(),
                    d.getUploadedAt());
        }
    }

    record DispatchResponse(
            UUID id,
            String referenceNo,
            String toTitle,
            String fromTitle,
            String address,
            String note,
            LocalDate dispatchDate,
            List<DocumentResponse> documents,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static DispatchResponse from(PostalDispatch d, List<PostalDispatchDocument> documents) {
            return new DispatchResponse(d.getId(), d.getReferenceNo(), d.getToTitle(), d.getFromTitle(), d.getAddress(),
                    d.getNote(), d.getDispatchDate(), documents.stream().map(DocumentResponse::from).toList(),
                    d.getCreatedAt(), d.getUpdatedAt());
        }
    }
}
