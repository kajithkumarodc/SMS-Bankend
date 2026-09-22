package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FeeStructureRepository extends JpaRepository<FeeStructure, UUID> {

    List<FeeStructure> findAllByOrderByCreatedAtDesc();

    /**
     * Optional class/academic-year filter. A structure matches the class filter
     * if it is specific to that class OR applies to every class (classId null);
     * a structure matches the year filter only on an exact academicYear match.
     * Either filter may be omitted (pass null) to not filter on it.
     */
    @Query("SELECT f FROM FeeStructure f WHERE "
            + "(:classId IS NULL OR f.classId = :classId OR f.classId IS NULL) AND "
            + "(:academicYear IS NULL OR f.academicYear = :academicYear) "
            + "ORDER BY f.createdAt DESC")
    List<FeeStructure> search(@Param("classId") UUID classId, @Param("academicYear") String academicYear);
}
