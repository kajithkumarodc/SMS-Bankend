package com.smsapp.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Lets a signed-in browser pick up roles/permissions granted since it logged in (e.g. a new module's
 * permissions added by a migration) without logging out. Lives under {@code /api/v1/me} rather than
 * {@code /api/v1/auth} because {@link CookieBearerTokenResolver} deliberately ignores the session cookie on
 * {@code /auth/**}, and this endpoint needs it.
 */
@RestController
public class SessionController {

    private final AuthService authService;
    private final AuthCookieFactory authCookieFactory;

    public SessionController(AuthService authService, AuthCookieFactory authCookieFactory) {
        this.authService = authService;
        this.authCookieFactory = authCookieFactory;
    }

    /**
     * Re-issues the caller's access token with their current roles/permissions and the same expiry, and
     * updates the cookie to match. Never extends the session. 401 if the user no longer exists.
     */
    @PostMapping("/api/v1/me/session")
    ResponseEntity<AuthService.SessionResponse> refresh(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Instant expiresAt = jwt.getExpiresAt();
        AuthService.SessionResponse response = authService.refreshSession(UUID.fromString(jwt.getSubject()), expiresAt);
        Duration remaining = Duration.between(Instant.now(), expiresAt);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.create(response.token(), remaining).toString())
                .body(response);
    }
}
