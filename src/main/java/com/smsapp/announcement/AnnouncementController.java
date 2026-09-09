package com.smsapp.announcement;

import com.smsapp.announcement.AnnouncementDtos.AnnouncementResponse;
import com.smsapp.announcement.AnnouncementDtos.CreateAnnouncementRequest;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * School-wide announcements. Anyone signed in to the tenant may read them
 * (admin, teacher, student, parent -- they are meant to be seen by everyone);
 * only a SCHOOL_ADMIN may post or remove one.
 */
@RestController
@RequestMapping("/api/v1/announcements")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    /** Post an announcement. SCHOOL_ADMIN only; a TEACHER/STUDENT/PARENT gets 403. */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<AnnouncementResponse> create(@Valid @RequestBody CreateAnnouncementRequest request,
                                                       Authentication authentication) {
        Announcement created = announcementService.create(
                tenantId(authentication), userId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AnnouncementResponse.from(created));
    }

    /** Announcements for the caller's tenant, newest first, paginated. Any authenticated role. */
    @GetMapping
    PagedModel<AnnouncementResponse> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication) {
        return new PagedModel<>(announcementService.list(tenantId(authentication), pageable)
                .map(AnnouncementResponse::from));
    }

    /** Remove an announcement. SCHOOL_ADMIN only. 404 if it is not in the caller's tenant. */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        announcementService.delete(tenantId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private static UUID tenantId(Authentication authentication) {
        return UUID.fromString(jwt(authentication).getClaimAsString("tenant_id"));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(jwt(authentication).getSubject());
    }

    private static Jwt jwt(Authentication authentication) {
        return (Jwt) authentication.getPrincipal();
    }
}
