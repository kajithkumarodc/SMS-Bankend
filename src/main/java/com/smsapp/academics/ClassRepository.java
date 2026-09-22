package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClassRepository extends JpaRepository<SchoolClass, UUID> {

    List<SchoolClass> findAllByOrderByName();

    boolean existsBySchoolIdAndName(UUID schoolId, String name);

    /** Teacher Panel (Phase 4): resolve class names for a set of assigned class ids. */
    List<SchoolClass> findByIdIn(java.util.Collection<UUID> ids);
}
