package com.smsapp.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {

    List<RolePermission> findByRoleId(UUID roleId);

    void deleteByRoleId(UUID roleId);
}
