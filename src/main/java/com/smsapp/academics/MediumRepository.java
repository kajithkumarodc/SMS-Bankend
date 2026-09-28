package com.smsapp.academics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MediumRepository extends JpaRepository<Medium, UUID> {

    List<Medium> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    /** Students or fee structures still pointing at the medium -- such a medium is deactivated, not deleted. */
    @Query(value = "SELECT (SELECT count(*) FROM students WHERE medium_id = :id)"
            + " + (SELECT count(*) FROM fee_structures WHERE medium_id = :id)", nativeQuery = true)
    long countUsages(UUID id);
}
