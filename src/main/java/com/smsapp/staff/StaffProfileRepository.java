package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

    List<StaffProfile> findAllByOrderByEmployeeCode();

    Optional<StaffProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByEmployeeCode(String employeeCode);

    /** The user ids that already have a profile -- used to build the "eligible users" picker. */
    @Query("select p.userId from StaffProfile p")
    List<UUID> findAllUserIds();
}
