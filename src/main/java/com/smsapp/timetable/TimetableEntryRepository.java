package com.smsapp.timetable;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TimetableEntryRepository extends JpaRepository<TimetableEntry, UUID> {

    List<TimetableEntry> findBySectionIdOrderByDayOfWeekAscTimeFromAsc(UUID sectionId);

    List<TimetableEntry> findBySectionIdAndSubjectGroupIdOrderByDayOfWeekAscTimeFromAsc(UUID sectionId, UUID subjectGroupId);

    List<TimetableEntry> findByStaffProfileIdOrderByDayOfWeekAscTimeFromAsc(UUID staffProfileId);

    List<TimetableEntry> findByStaffProfileIdAndDayOfWeek(UUID staffProfileId, int dayOfWeek);

    boolean existsBySubjectId(UUID subjectId);

    boolean existsBySubjectGroupId(UUID subjectGroupId);

    void deleteBySectionIdAndSubjectGroupId(UUID sectionId, UUID subjectGroupId);
}
