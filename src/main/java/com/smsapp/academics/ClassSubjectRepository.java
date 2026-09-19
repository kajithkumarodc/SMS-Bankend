package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClassSubjectRepository extends JpaRepository<ClassSubject, UUID> {

    boolean existsByClassIdAndSubjectId(UUID classId, UUID subjectId);

    /** The subjects assigned to one class, ordered by name. */
    @Query("select s from Subject s, ClassSubject cs "
            + "where cs.subjectId = s.id and cs.classId = :classId "
            + "order by s.name")
    List<Subject> findSubjectsForClass(@Param("classId") UUID classId);
}
