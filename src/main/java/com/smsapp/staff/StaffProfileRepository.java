package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

    List<StaffProfile> findByTenantIdOrderByEmployeeCode(UUID tenantId);

    Optional<StaffProfile> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<StaffProfile> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);

    boolean existsByTenantIdAndEmployeeCode(UUID tenantId, String employeeCode);

    /** The user ids that already have a profile in this tenant -- used to build the "eligible users" picker. */
    @Query("select p.userId from StaffProfile p where p.tenantId = :tenantId")
    List<UUID> findUserIdsByTenantId(UUID tenantId);
}
