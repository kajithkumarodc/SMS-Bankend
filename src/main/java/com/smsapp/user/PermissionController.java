package com.smsapp.user;

import com.smsapp.user.PermissionDtos.CreatePermissionRequest;
import com.smsapp.user.PermissionDtos.PermissionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The permission catalog (RBAC Phase 1). SCHOOL_ADMIN/SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/v1/permissions")
@PreAuthorize(Roles.HAS_ADMIN)
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    List<PermissionResponse> list() {
        return permissionService.listAll().stream().map(PermissionResponse::from).toList();
    }

    @PostMapping
    ResponseEntity<PermissionResponse> create(@Valid @RequestBody CreatePermissionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(PermissionResponse.from(permissionService.create(request.name())));
    }
}
