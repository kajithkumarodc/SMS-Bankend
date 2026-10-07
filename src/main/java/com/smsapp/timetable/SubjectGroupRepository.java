package com.smsapp.timetable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SubjectGroupRepository extends JpaRepository<SubjectGroup, UUID> {

    List<SubjectGroup> findAllByOrderByName();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    @Query("select distinct g from SubjectGroup g join g.sectionIds s where s = :sectionId order by g.name")
    List<SubjectGroup> findBySection(@Param("sectionId") UUID sectionId);

    @Query("select count(g) > 0 from SubjectGroup g join g.subjectIds s where s = :subjectId")
    boolean existsBySubject(@Param("subjectId") UUID subjectId);
}
