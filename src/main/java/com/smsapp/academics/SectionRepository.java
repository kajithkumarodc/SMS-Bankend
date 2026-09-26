package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SectionRepository extends JpaRepository<Section, UUID> {

    List<Section> findAllByOrderByName();

    boolean existsByClassIdAndName(UUID classId, String name);

    /** Teacher Panel (Phase 4): every section under a set of assigned classes. */
    List<Section> findByClassIdIn(java.util.Collection<UUID> classIds);

    java.util.Optional<Section> findByIdAndClassId(UUID id, UUID classId);

    /** Students currently placed in a section -- a section can't be deleted while this is above zero. */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT count(*) FROM students WHERE section_id = :sectionId", nativeQuery = true)
    long countStudentsInSection(UUID sectionId);
}
