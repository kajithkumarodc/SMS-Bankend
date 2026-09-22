package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SectionRepository extends JpaRepository<Section, UUID> {

    List<Section> findAllByOrderByName();

    boolean existsByClassIdAndName(UUID classId, String name);

    /** Teacher Panel (Phase 4): every section under a set of assigned classes. */
    List<Section> findByClassIdIn(java.util.Collection<UUID> classIds);
}
