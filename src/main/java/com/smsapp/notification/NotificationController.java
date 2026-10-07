package com.smsapp.notification;

import com.smsapp.notification.NotificationService.Inbox;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The signed-in user's own notifications (the bell in the header). Any authenticated user; nobody sees another's. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    record NotificationResponse(UUID id, String type, String title, String message, String link, boolean read,
                                OffsetDateTime createdAt) {

        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getLink(),
                    n.getReadAt() != null, n.getCreatedAt());
        }
    }

    record InboxResponse(long unread, List<NotificationResponse> items) {
    }

    @GetMapping
    InboxResponse inbox(Authentication authentication) {
        Inbox inbox = service.inbox(userId(authentication));
        return new InboxResponse(inbox.unread(), inbox.items().stream().map(NotificationResponse::from).toList());
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> markRead(@PathVariable UUID id, Authentication authentication) {
        service.markRead(id, userId(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    ResponseEntity<Void> markAllRead(Authentication authentication) {
        service.markAllRead(userId(authentication));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
