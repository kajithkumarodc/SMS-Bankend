package com.smsapp.announcement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the announcements API. Entities are never exposed directly (plan section 7.1d). */
final class AnnouncementDtos {

    private AnnouncementDtos() {
    }

    record CreateAnnouncementRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20_000) String body) {
    }

    record AnnouncementResponse(
            UUID id,
            String title,
            String body,
            UUID createdBy,
            OffsetDateTime createdAt) {

        static AnnouncementResponse from(Announcement announcement) {
            return new AnnouncementResponse(announcement.getId(), announcement.getTitle(), announcement.getBody(),
                    announcement.getCreatedBy(), announcement.getCreatedAt());
        }
    }
}
