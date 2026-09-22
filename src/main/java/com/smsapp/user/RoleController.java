package com.smsapp.user;

import com.smsapp.user.RoleDtos.CreateRoleRequest;
import com.smsapp.user.RoleDtos.RoleResponse;
import com.smsapp.user.RoleDtos.UpdateRolePermissionsRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Roles + role-permission grants (RBAC Phase 1). SCHOOL_ADMIN/SUPER_ADMIN only -- see {@link Roles#HAS_ADMIN}. */
@RestController
@RequestMapping("/api/v1/roles")
@PreAuthorize(Roles.HAS_ADMIN)
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    List<RoleResponse> list() {
        return roleService.listAll();
    }

    @PostMapping
    ResponseEntity<Role> create(@Valid @RequestBody CreateRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roleService.create(request.name()));
    }

    /** Replaces the full permission set for a role. 404 if the role doesn't exist, 400 if a permission id is unknown. */
    @PutMapping("/{id}/permissions")
    ResponseEntity<Void> updatePermissions(@PathVariable UUID id, @Valid @RequestBody UpdateRolePermissionsRequest request) {
        roleService.replacePermissions(id, request.permissionIds());
        return ResponseEntity.noContent().build();
    }
}
