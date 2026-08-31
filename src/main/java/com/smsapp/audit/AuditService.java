package com.smsapp.audit;

import com.smsapp.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Writes immutable audit records. Both entry points run in their OWN transaction
 * ({@code REQUIRES_NEW}) so that:
 * <ul>
 *   <li>an audit-write failure can never poison or roll back the business
 *       operation it describes, and</li>
 *   <li>an audit entry survives even when the surrounding operation rolls back
 *       (a failed login still records {@code LOGIN_FAILED}).</li>
 * </ul>
 * Failures are logged and swallowed -- auditing must not lock anyone out.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** Records an action by the current user in the current tenant. No-op if there is no tenant context. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String action, String entityType, UUID entityId, Map<String, Object> details) {
        UUID tenantId = currentTenantId();
        if (tenantId == null) {
            return;
        }
        write(tenantId, currentActorId(), action, entityType, entityId, details);
    }

    /** Records an action with an explicit tenant and actor -- for flows where the context is not yet established. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAs(UUID tenantId, UUID actorUserId, String action, String entityType, UUID entityId,
                      Map<String, Object> details) {
        write(tenantId, actorUserId, action, entityType, entityId, details);
    }

    private void write(UUID tenantId, UUID actorUserId, String action, String entityType, UUID entityId,
                       Map<String, Object> details) {
        try {
            AuditLog entry = new AuditLog();
            entry.setTenantId(tenantId);
            entry.setActorUserId(actorUserId);
            entry.setAction(action);
            entry.setEntityType(entityType);
            entry.setEntityId(entityId);
            entry.setDetails(details);
            auditLogRepository.save(entry);
        } catch (RuntimeException ex) {
            log.warn("Failed to write audit entry action={} entityType={} entityId={}", action, entityType, entityId, ex);
        }
    }

    private static UUID currentTenantId() {
        String tenant = TenantContext.getCurrentTenant();
        return tenant == null ? null : UUID.fromString(tenant);
    }

    private static UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
