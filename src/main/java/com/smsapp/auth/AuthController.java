package com.smsapp.auth;

import com.smsapp.user.UserActivationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    private final UserActivationService userActivationService;

    public AuthController(AuthService authService, AuthCookieFactory authCookieFactory,
                          UserActivationService userActivationService) {
        this.authService = authService;
        this.authCookieFactory = authCookieFactory;
        this.userActivationService = userActivationService;
    }

    @PostMapping("/login")
    ResponseEntity<AuthService.LoginResponse> login(@Valid @RequestBody LoginPayload payload) {
        AuthService.LoginResponse response = authService.login(
                new AuthService.LoginRequest(payload.email(), payload.password()));

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

    /**
     * Consumes a portal-invitation activation link (plan Phase 4.5 part 14) and sets the user's own
     * password -- public, since the account has no password to authenticate with yet. The token
     * itself is the credential; an invalid/expired/already-used one reports the same generic 400.
     */
    @PostMapping("/activate")
    ResponseEntity<Void> activate(@Valid @RequestBody ActivatePayload payload) {
        userActivationService.activate(payload.token(), payload.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record LoginPayload(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record ActivatePayload(@NotBlank String token, @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }
}
