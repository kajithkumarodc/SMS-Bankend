package com.smsapp.admission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdmissionCycleRepository extends JpaRepository<AdmissionCycle, UUID> {

    List<AdmissionCycle> findAllByOrderByCreatedAtDesc();

    /** The public application page: whichever cycle is OPEN for this school right now, if any. */
    Optional<AdmissionCycle> findFirstBySchoolIdAndStatusOrderByOpenDateDesc(UUID schoolId, String status);

    /** Enforced again here (belt-and-suspenders alongside the DB partial-unique index) before opening a new one. */
    boolean existsBySchoolIdAndStatus(UUID schoolId, String status);

    @Query("select c from AdmissionCycle c where c.schoolId = :schoolId and c.status = 'OPEN' and c.id <> :excludingId")
    List<AdmissionCycle> findOtherOpenCyclesForSchool(@Param("schoolId") UUID schoolId, @Param("excludingId") UUID excludingId);
}
