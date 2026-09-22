package com.smsapp.user;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.user.RoleDtos.RoleResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Database-driven roles + role-permission grants (RBAC Phase 1, plan
 * "Users & Permissions" / decisions doc #1). Role names created here become
 * usable in a user's role assignment immediately; they do NOT automatically
 * get a {@code @PreAuthorize(hasRole(...))} carve-out anywhere -- that only
 * exists for the original four roles' well-known endpoints. A custom role's
 * access is entirely determined by the permissions assigned to it here,
 * checked via {@code hasAuthority(...)} on the endpoints that adopt it.
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final AuditService auditService;

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository,
                       RolePermissionRepository rolePermissionRepository, AuditService auditService) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listAll() {
        Map<UUID, Permission> permissionsById = permissionRepository.findAll().stream()
                .collect(Collectors.toMap(Permission::getId, p -> p));
        List<Role> roles = roleRepository.findAllByOrderByName();
        List<RolePermission> grants = rolePermissionRepository.findAll();
        Map<UUID, List<String>> permissionNamesByRole = grants.stream()
                .filter(g -> permissionsById.containsKey(g.getPermissionId()))
                .collect(Collectors.groupingBy(RolePermission::getRoleId,
                        Collectors.mapping(g -> permissionsById.get(g.getPermissionId()).getName(), Collectors.toList())));

        return roles.stream()
                .map(r -> new RoleResponse(r.getId(), r.getName(),
                        permissionNamesByRole.getOrDefault(r.getId(), List.of()).stream().sorted().toList()))
                .toList();
    }

    /** @throws ApiException 409 if a role with that name already exists. */
    @Transactional
    public Role create(String name) {
        String trimmed = name.trim();
        if (roleRepository.existsByName(trimmed)) {
            throw new ApiException("A role named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        Role role = new Role();
        role.setName(trimmed);
        Role saved;
        try {
            saved = roleRepository.saveAndFlush(role);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A role named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.ROLE_CREATED, AuditActions.ROLE, saved.getId(), Map.of("name", saved.getName()));
        return saved;
    }

    /**
     * Replaces the full permission set granted to a role.
     *
     * @throws ApiException 404 if the role does not exist, 400 if any permission id is unknown.
     */
    @Transactional
    public void replacePermissions(UUID roleId, Set<UUID> permissionIds) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ApiException("Role not found", HttpStatus.NOT_FOUND));

        if (!permissionIds.isEmpty()) {
            long found = permissionRepository.findAllById(permissionIds).size();
            if (found != permissionIds.size()) {
                throw new ApiException("One or more permission ids do not exist", HttpStatus.BAD_REQUEST);
            }
        }

        rolePermissionRepository.deleteByRoleId(roleId);
        List<RolePermission> grants = permissionIds.stream().map(pid -> new RolePermission(roleId, pid)).toList();
        rolePermissionRepository.saveAll(grants);

        auditService.log(AuditActions.ROLE_PERMISSIONS_UPDATED, AuditActions.ROLE, role.getId(),
                Map.of("permissionCount", permissionIds.size()));
    }
}
