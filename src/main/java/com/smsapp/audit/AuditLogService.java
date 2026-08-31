package com.smsapp.audit;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read side of the audit log. Runs in a transaction so the RLS tenant var is set. */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Tenant-scoped search with optional {@code entityType} and half-open
     * {@code [from, to)} time-range filters (null = unbounded). Ordering comes
     * from the {@link Pageable}.
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> search(UUID tenantId, String entityType, OffsetDateTime from, OffsetDateTime to,
                                 Pageable pageable) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (entityType != null) {
                predicates.add(cb.equal(root.get("entityType"), entityType));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return auditLogRepository.findAll(spec, pageable);
    }
}
