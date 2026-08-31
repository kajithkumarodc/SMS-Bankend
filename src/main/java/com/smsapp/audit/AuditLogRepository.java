package com.smsapp.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * Read + insert only. The application never updates or deletes audit rows
 * (migration V8 revokes the grants and defines no UPDATE/DELETE RLS policy).
 * The read side uses {@link JpaSpecificationExecutor} so the optional
 * entity-type / date-range filters compose without null-parameter typing issues.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {
}
