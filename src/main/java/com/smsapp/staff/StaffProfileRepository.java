package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

    List<StaffProfile> findAllByOrderByEmployeeCode();

    Optional<StaffProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByEmployeeCode(String employeeCode);

    long countByDepartmentId(UUID departmentId);

    long countByDesignationId(UUID designationId);

    /** Keeps the department name shown on staff profiles (the text column) in step with a rename. */
    @Modifying
    @Query("update StaffProfile p set p.department = :name where p.departmentId = :departmentId")
    int renameDepartment(@Param("departmentId") UUID departmentId, @Param("name") String name);

    /** Keeps the designation name shown on staff profiles (the text column) in step with a rename. */
    @Modifying
    @Query("update StaffProfile p set p.designation = :name where p.designationId = :designationId")
    int renameDesignation(@Param("designationId") UUID designationId, @Param("name") String name);

    /** The user ids that already have a profile -- used to build the "eligible users" picker. */
    @Query("select p.userId from StaffProfile p")
    List<UUID> findAllUserIds();
}
