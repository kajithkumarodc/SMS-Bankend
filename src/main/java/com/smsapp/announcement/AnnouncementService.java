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
 * School-wide announcements. Reads are tenant-wide (everyone in the school sees
 * the same list -- not ownership-scoped); writes are SCHOOL_ADMIN only, enforced
 * at the controller. Every query is tenant-scoped on top of the RLS policy.
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
    public Announcement create(UUID tenantId, UUID createdByUserId, CreateAnnouncementRequest request) {
        Announcement announcement = new Announcement();
        announcement.setTenantId(tenantId);
        announcement.setTitle(request.title().trim());
        announcement.setBody(request.body().trim());
        announcement.setCreatedBy(createdByUserId);
        Announcement saved = announcementRepository.save(announcement);

        auditService.log(AuditActions.ANNOUNCEMENT_CREATED, AuditActions.ANNOUNCEMENT, saved.getId(),
                Map.of("title", saved.getTitle()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Announcement> list(UUID tenantId, Pageable pageable) {
        return announcementRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable);
    }

    /**
     * @throws ApiException 404 if the announcement is not in the caller's tenant
     *         (so a cross-tenant id is indistinguishable from a missing one).
     */
    @Transactional
    public void delete(UUID tenantId, UUID id) {
        Announcement announcement = announcementRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ApiException("Announcement not found", HttpStatus.NOT_FOUND));
        announcementRepository.delete(announcement);

        auditService.log(AuditActions.ANNOUNCEMENT_DELETED, AuditActions.ANNOUNCEMENT, id,
                Map.of("title", announcement.getTitle()));
    }
}
