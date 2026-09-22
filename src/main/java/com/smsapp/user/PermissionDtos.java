package com.smsapp.user;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request/response payloads for the permissions API (RBAC Phase 1). */
final class PermissionDtos {

    private PermissionDtos() {
    }

    record CreatePermissionRequest(@NotEmpty @Size(max = 100) String name) {
    }

    record PermissionResponse(UUID id, String name) {

        static PermissionResponse from(Permission permission) {
            return new PermissionResponse(permission.getId(), permission.getName());
        }
    }
}
