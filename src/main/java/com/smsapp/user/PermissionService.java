package com.smsapp.user;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** The permission catalog (RBAC Phase 1). Seeded by V22; extensible from the admin screen. */
@Service
public class PermissionService {

    private final PermissionRepository permissionRepository;
    private final AuditService auditService;

    public PermissionService(PermissionRepository permissionRepository, AuditService auditService) {
        this.permissionRepository = permissionRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Permission> listAll() {
        return permissionRepository.findAllByOrderByName();
    }

    /** @throws ApiException 409 if a permission with that name already exists. */
    @Transactional
    public Permission create(String name) {
        String trimmed = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (permissionRepository.existsByName(trimmed)) {
            throw new ApiException("A permission named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        Permission permission = new Permission();
        permission.setName(trimmed);
        Permission saved;
        try {
            saved = permissionRepository.saveAndFlush(permission);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A permission named '" + trimmed + "' already exists", HttpStatus.CONFLICT);
        }
        auditService.log(AuditActions.PERMISSION_CREATED, AuditActions.PERMISSION, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }
}
