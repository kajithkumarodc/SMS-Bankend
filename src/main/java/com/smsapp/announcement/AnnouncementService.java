package com.smsapp.announcement;

import com.smsapp.announcement.AnnouncementDtos.CreateAnnouncementRequest;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * School-wide announcements. Reads are visible to everyone (not ownership-scoped);
 * writes are SCHOOL_ADMIN only, enforced at the controller.
 */
@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AuditService auditService;

    public AnnouncementService(AnnouncementRepository announcementRepository, AuditService auditService) {
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
    }

    @Transactional
    public Announcement create(UUID createdByUserId, CreateAnnouncementRequest request) {
        Announcement announcement = new Announcement();
        announcement.setTitle(request.title().trim());
        announcement.setBody(request.body().trim());
        announcement.setCreatedBy(createdByUserId);
        Announcement saved = announcementRepository.save(announcement);

        auditService.log(AuditActions.ANNOUNCEMENT_CREATED, AuditActions.ANNOUNCEMENT, saved.getId(),
                Map.of("title", saved.getTitle()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Announcement> list(Pageable pageable) {
        return announcementRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * @throws ApiException 404 if no such announcement.
     */
    @Transactional
    public void delete(UUID id) {
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new ApiException("Announcement not found", HttpStatus.NOT_FOUND));
        announcementRepository.delete(announcement);

        auditService.log(AuditActions.ANNOUNCEMENT_DELETED, AuditActions.ANNOUNCEMENT, id,
                Map.of("title", announcement.getTitle()));
    }
}
