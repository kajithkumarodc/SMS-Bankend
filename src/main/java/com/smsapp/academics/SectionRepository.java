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

    /** The hidden whole-class section, present only while the class has no real sections (V39). */
    java.util.Optional<Section> findByClassIdAndDefaultSectionTrue(UUID classId);

    long countByClassIdAndDefaultSectionFalse(UUID classId);

    /** Students currently placed in a section -- a section can't be deleted while this is above zero. */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT count(*) FROM students WHERE section_id = :sectionId", nativeQuery = true)
    long countStudentsInSection(UUID sectionId);

    /** Whether any class has a real (named) section with this name. */
    @org.springframework.data.jpa.repository.Query("select count(s) > 0 from Section s where s.name = :name and s.defaultSection = false")
    boolean existsRealSectionNamed(@org.springframework.data.repository.query.Param("name") String name);

    /** Renames every class's real section called {@code from} -- keeps classes in step with a rename in the Sections list. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Section s set s.name = :to where s.name = :from and s.defaultSection = false")
    int renameRealSections(@org.springframework.data.repository.query.Param("from") String from,
                           @org.springframework.data.repository.query.Param("to") String to);

    /** Timetable periods of a section -- it can't be deleted while it has any (they would be lost with it). */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT count(*) FROM timetable_entries WHERE section_id = :sectionId", nativeQuery = true)
    long countTimetablePeriods(UUID sectionId);

    /** Subject groups that cover a section. */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT count(*) FROM subject_group_sections WHERE section_id = :sectionId", nativeQuery = true)
    long countSubjectGroups(UUID sectionId);
}
