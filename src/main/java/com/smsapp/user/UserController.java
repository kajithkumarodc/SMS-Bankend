package com.smsapp.user;

import com.smsapp.user.UserDtos.CreateUserRequest;
import com.smsapp.user.UserDtos.CreateUserResponse;
import com.smsapp.user.UserDtos.ResetPasswordResponse;
import com.smsapp.user.UserDtos.UpdateUserRequest;
import com.smsapp.user.UserDtos.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Local user provisioning + role assignment (RBAC Phase 1). SCHOOL_ADMIN/SUPER_ADMIN
 * only. Until this controller, no endpoint could create a {@code User} row at all.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize(Roles.HAS_ADMIN)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    ResponseEntity<CreateUserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.create(request));
    }

    @GetMapping
    List<UserResponse> list() {
        return userService.listAll();
    }

    @GetMapping("/{id}")
    UserResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    @PutMapping("/{id}")
    UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    /** Forces a new temporary password. Returned once in the response -- no email/SMS provider required. */
    @PatchMapping("/{id}/reset-password")
    ResetPasswordResponse resetPassword(@PathVariable UUID id) {
        return userService.resetPassword(id);
    }
}
