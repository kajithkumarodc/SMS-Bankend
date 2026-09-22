package com.smsapp.user;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request/response payloads for the roles API (RBAC Phase 1). Entities are never exposed directly. */
final class RoleDtos {

    private RoleDtos() {
    }

    record CreateRoleRequest(@NotEmpty @Size(max = 50) String name) {
    }

    /** Body for {@code PUT /api/v1/roles/{id}/permissions} -- replaces the full permission set for the role. */
    record UpdateRolePermissionsRequest(@NotNull Set<UUID> permissionIds) {
    }

    record RoleResponse(UUID id, String name, List<String> permissionNames) {
    }
}
