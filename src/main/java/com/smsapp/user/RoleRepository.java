package com.smsapp.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    @Query("select role.name from Role role join UserRole userRole on userRole.roleId = role.id where userRole.userId = :userId")
    List<String> findNamesByUserId(UUID userId);

    /**
     * Distinct permission names granted (via {@code role_permissions}) to any role
     * this user holds. Baked into the JWT {@code permissions} claim at login, same
     * as {@link #findNamesByUserId} is for {@code roles} (RBAC Phase 1).
     */
    @Query("select distinct permission.name from Role role "
            + "join UserRole userRole on userRole.roleId = role.id "
            + "join RolePermission rolePermission on rolePermission.roleId = role.id "
            + "join Permission permission on permission.id = rolePermission.permissionId "
            + "where userRole.userId = :userId")
    List<String> findPermissionNamesByUserId(UUID userId);

    List<Role> findAllByOrderByName();

    Optional<Role> findByName(String name);

    boolean existsByName(String name);
}
