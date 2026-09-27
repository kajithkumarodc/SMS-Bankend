package com.smsapp.homework;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface HomeworkRepository extends JpaRepository<Homework, UUID> {

    /** A teacher's or admin's list for one section, newest first. */
    Page<Homework> findBySectionIdOrderByAssignedDateDescCreatedAtDesc(UUID sectionId, Pageable pageable);

    /** Narrowed to one subject within a section -- the subject-teacher's own view. */
    Page<Homework> findBySectionIdAndSubjectIdOrderByAssignedDateDescCreatedAtDesc(
            UUID sectionId, UUID subjectId, Pageable pageable);

    /**
     * The student/parent portal read: everything set for the student's section,
     * newest first. Takes a collection so a student who has moved section mid-year
     * can still be shown their current section's list without a second query shape.
     */
    List<Homework> findBySectionIdInOrderByAssignedDateDescCreatedAtDesc(Collection<UUID> sectionIds);

    /** Portal read narrowed to a window, so a parent is not handed the whole year. */
    List<Homework> findBySectionIdInAndAssignedDateGreaterThanEqualOrderByAssignedDateDescCreatedAtDesc(
            Collection<UUID> sectionIds, LocalDate from);
}
