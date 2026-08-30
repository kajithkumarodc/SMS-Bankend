package com.smsapp.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieFactory authCookieFactory;

    public AuthController(AuthService authService, AuthCookieFactory authCookieFactory) {
        this.authService = authService;
        this.authCookieFactory = authCookieFactory;
    }

    @PostMapping("/login")
    ResponseEntity<AuthService.LoginResponse> login(@Valid @RequestBody LoginPayload payload) {
        AuthService.LoginResponse response = authService.login(
                new AuthService.LoginRequest(payload.schoolIdentifier(), payload.email(), payload.password()));

        // The token is returned in the body (convenient for non-browser clients) and
        // also set as an httpOnly cookie so browser code never has to hold it.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.create(response.token()).toString())
                .body(response);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.clear().toString())
                .build();
    }

    public record LoginPayload(@NotBlank String schoolIdentifier, @Email @NotBlank String email,
                               @NotBlank String password) {
    }
}
