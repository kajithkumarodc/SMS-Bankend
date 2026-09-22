package com.smsapp.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request/response payloads for the users API (RBAC Phase 1 / local user management). */
final class UserDtos {

    private UserDtos() {
    }

    /**
     * {@code initialPassword} is optional -- when blank, the server generates a
     * random temporary password (returned once in the response, {@code mustChangePassword=true})
     * so an admin can hand it to the user without any email/SMS provider being configured.
     */
    record CreateUserRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String fullName,
            @Size(min = 8, max = 100) String initialPassword,
            @NotEmpty Set<UUID> roleIds) {
    }

    record UpdateUserRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank String status,
            @NotEmpty Set<UUID> roleIds) {
    }

    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    /** Returned once, right after an admin-triggered reset -- the only time a plaintext password is ever returned. */
    record ResetPasswordResponse(String temporaryPassword) {
    }

    record UserResponse(
            UUID id,
            String email,
            String fullName,
            String status,
            boolean mustChangePassword,
            List<String> roles) {

        static UserResponse from(User user, List<String> roles) {
            return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getStatus(),
                    user.isMustChangePassword(), roles);
        }
    }

    /** Body for {@code CREATE}'s response -- includes the generated password only when one was auto-generated. */
    record CreateUserResponse(UserResponse user, String generatedPassword) {
    }
}
