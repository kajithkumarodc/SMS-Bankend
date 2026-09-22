package com.smsapp.student;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StudentIdentificationRepository extends JpaRepository<StudentIdentification, UUID> {

    List<StudentIdentification> findByStudentIdOrderByCreatedAtDesc(UUID studentId);
}
