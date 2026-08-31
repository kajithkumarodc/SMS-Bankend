package com.smsapp.audit;

import com.smsapp.user.Roles;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * "Who did what" for a school admin. SCHOOL_ADMIN only; tenant-scoped; paginated;
 * filterable by {@code entityType} and a {@code [from, to]} date range (both
 * inclusive of the whole day).
 */
@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    PagedModel<AuditLogEntry> list(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication) {

        Jwt jwt = (Jwt) authentication.getPrincipal();
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));

        OffsetDateTime fromTs = from == null ? null : from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toTs = to == null ? null : to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        return new PagedModel<>(auditLogService.search(tenantId, emptyToNull(entityType), fromTs, toTs, pageable)
                .map(AuditLogEntry::from));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record AuditLogEntry(
            UUID id,
            UUID actorUserId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> details,
            OffsetDateTime createdAt) {

        static AuditLogEntry from(AuditLog log) {
            return new AuditLogEntry(log.getId(), log.getActorUserId(), log.getAction(), log.getEntityType(),
                    log.getEntityId(), log.getDetails(), log.getCreatedAt());
        }
    }
}
