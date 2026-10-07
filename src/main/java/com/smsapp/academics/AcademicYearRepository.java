package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcademicYearRepository extends JpaRepository<AcademicYear, UUID> {

    List<AcademicYear> findAllByOrderByStartDateDesc();

    Optional<AcademicYear> findByCurrentTrue();

    /** The academic year whose dates include {@code date} (pass the same date twice). */
    Optional<AcademicYear> findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
            LocalDate onOrAfterStart, LocalDate onOrBeforeEnd);

    /** The latest academic year on record, used as the pattern for years not created yet. */
    Optional<AcademicYear> findFirstByOrderByStartDateDesc();

    boolean existsByName(String name);
}
