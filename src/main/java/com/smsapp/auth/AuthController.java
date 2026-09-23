package com.smsapp.auth;

import com.smsapp.user.UserActivationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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

        // The token (and now a refreshToken) is returned in the body -- convenient
        // for non-browser clients, which use the Authorization header instead of
        // the cookie (see CookieBearerTokenResolver) -- and the access token is
        // also set as an httpOnly cookie so browser code never has to hold it.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.create(response.token()).toString())
                .body(response);
    }

    /** Exchanges a still-active refresh token for a new access token + a new, rotated refresh token. */
    @PostMapping("/refresh")
    ResponseEntity<AuthService.RefreshResponse> refresh(@Valid @RequestBody RefreshPayload payload) {
        AuthService.RefreshResponse response = authService.refresh(payload.refreshToken());

        // Mirrors /login: refresh the browser's cookie too, so a browser session
        // that happens to call this endpoint stays consistent either way.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.create(response.token()).toString())
                .body(response);
    }

    /**
     * Clears the access-token cookie (the web client's session) and, when a mobile
     * client includes its refresh token, revokes that token server-side too.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestBody(required = false) LogoutPayload payload) {
        if (payload != null) {
            authService.logout(payload.refreshToken());
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.clear().toString())
                .build();
    }

    /** Revokes every refresh token for the current user -- "sign out of all devices." */
    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(Authentication authentication) {
        authService.logoutAll(UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject()));
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

    public record RefreshPayload(@NotBlank String refreshToken) {
    }

    public record LogoutPayload(String refreshToken) {
    }
}
