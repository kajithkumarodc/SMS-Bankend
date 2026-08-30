package com.smsapp.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    ResponseEntity<AuthService.LoginResponse> login(@Valid @RequestBody LoginPayload payload) {
        return ResponseEntity.ok(authService.login(
                new AuthService.LoginRequest(payload.tenantId(), payload.email(), payload.password())));
    }

    public record LoginPayload(@NotBlank String tenantId, @Email @NotBlank String email, @NotBlank String password) {
    }
}
