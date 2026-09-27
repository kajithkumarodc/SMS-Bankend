package com.smsapp.homework;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeworkSubmissionRepository extends JpaRepository<HomeworkSubmission, UUID> {

    /** Every recorded submission for one piece of homework (absent students are PENDING). */
    List<HomeworkSubmission> findByHomeworkId(UUID homeworkId);

    Optional<HomeworkSubmission> findByHomeworkIdAndStudentId(UUID homeworkId, UUID studentId);

    /**
     * One student's recorded states across a set of homework, for the portal list --
     * one query rather than one per row.
     */
    List<HomeworkSubmission> findByStudentIdAndHomeworkIdIn(UUID studentId, Collection<UUID> homeworkIds);

    void deleteByHomeworkId(UUID homeworkId);
}
