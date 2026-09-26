package com.smsapp.frontoffice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request/response payloads for the Setup Front Office API. */
final class FrontOfficeSetupDtos {

    private FrontOfficeSetupDtos() {
    }

    /** Body for adding or editing an entry (Purpose / Complaint Type / Source / Reference). */
    record SetupItemRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {
    }

    record SetupItem(UUID id, String name, String description) {
    }

    /**
     * What a delete did: {@link #DELETED} (nothing used it) or {@link #DEACTIVATED} (hidden from the forms and
     * this list, kept for the {@code usageCount} records that use it).
     */
    record DeleteResult(String outcome, long usageCount) {
        static final String DELETED = "DELETED";
        static final String DEACTIVATED = "DEACTIVATED";
    }
}
