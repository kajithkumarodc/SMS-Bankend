package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClassTeacherRepository extends JpaRepository<ClassTeacher, UUID> {

    List<ClassTeacher> findBySectionId(UUID sectionId);

    void deleteBySectionId(UUID sectionId);

    boolean existsBySectionId(UUID sectionId);
}
