package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface ClassSubjectRepository extends JpaRepository<ClassSubject, UUID> {

    boolean existsByTenantIdAndClassIdAndSubjectId(UUID tenantId, UUID classId, UUID subjectId);

    /** The subjects assigned to one class, ordered by name. */
    @Query("select s from Subject s, ClassSubject cs "
            + "where cs.subjectId = s.id and cs.tenantId = :tenantId and cs.classId = :classId "
            + "order by s.name")
    List<Subject> findSubjectsForClass(@Param("tenantId") UUID tenantId, @Param("classId") UUID classId);
}
