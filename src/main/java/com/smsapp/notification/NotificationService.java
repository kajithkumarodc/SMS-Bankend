package com.smsapp.notification;

import com.smsapp.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * In-app notifications. Other features call {@link #notify} (inside their own transaction, so a notification is
 * only kept if the thing it announces is); every user reads and marks only their own.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /** A user's notifications for the bell: the unread count and the 30 latest, newest first. */
    public record Inbox(long unread, List<Notification> items) {
    }

    /** Sends the same notification to each recipient (duplicates in {@code recipientUserIds} are ignored). */
    @Transactional
    public void notify(Collection<UUID> recipientUserIds, String type, String title, String message, String link, UUID entityId) {
        recipientUserIds.stream().distinct().forEach(recipient -> {
            Notification notification = new Notification();
            notification.setRecipientUserId(recipient);
            notification.setType(type);
            notification.setTitle(limit(title, 200));
            notification.setMessage(limit(message, 1000));
            notification.setLink(link);
            notification.setEntityId(entityId);
            repository.save(notification);
        });
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID userId) {
        return new Inbox(repository.countByRecipientUserIdAndReadAtIsNull(userId),
                repository.findTop30ByRecipientUserIdOrderByCreatedAtDesc(userId));
    }

    /** @throws ApiException 404 if the notification does not exist or belongs to someone else. */
    @Transactional
    public void markRead(UUID id, UUID userId) {
        Notification notification = repository.findByIdAndRecipientUserId(id, userId)
                .orElseThrow(() -> new ApiException("Notification not found", HttpStatus.NOT_FOUND));
        if (notification.getReadAt() == null) {
            notification.setReadAt(OffsetDateTime.now());
            repository.save(notification);
        }
    }

    @Transactional
    public void markAllRead(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<Notification> unread = repository.findByRecipientUserIdAndReadAtIsNull(userId);
        unread.forEach(n -> n.setReadAt(now));
        repository.saveAll(unread);
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
