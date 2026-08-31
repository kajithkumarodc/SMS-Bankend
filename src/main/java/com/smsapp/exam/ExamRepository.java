package com.smsapp.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface ExamRepository extends JpaRepository<Exam, UUID> {

    List<Exam> findByTenantIdAndClassIdOrderByExamDateDesc(UUID tenantId, UUID classId);

    Optional<Exam> findByIdAndTenantId(UUID id, UUID tenantId);
}
