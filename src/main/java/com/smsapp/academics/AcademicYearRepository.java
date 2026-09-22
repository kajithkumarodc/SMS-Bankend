package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcademicYearRepository extends JpaRepository<AcademicYear, UUID> {

    List<AcademicYear> findAllByOrderByStartDateDesc();

    Optional<AcademicYear> findByCurrentTrue();

    boolean existsByName(String name);
}
