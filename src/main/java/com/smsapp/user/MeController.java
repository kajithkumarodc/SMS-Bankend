package com.smsapp.user;

import com.smsapp.user.UserDtos.ChangePasswordRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService userService;

    public MeController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    Map<String, Object> currentUser(Authentication authentication) {
        Jwt jwt = jwt(authentication);
        return Map.of(
                "userId", jwt.getSubject(),
                "roles", jwt.getClaimAsStringList("roles"),
                "permissions", jwt.getClaimAsStringList("permissions") == null
                        ? java.util.List.of() : jwt.getClaimAsStringList("permissions"));
    }

    /**
     * Self-service password change -- any authenticated user, including one whose
     * account was just created by an admin with {@code mustChangePassword=true}.
     * 400 if {@code currentPassword} does not match.
     */
    @PostMapping("/change-password")
    ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
        UUID userId = UUID.fromString(jwt(authentication).getSubject());
        userService.changeOwnPassword(userId, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    private static Jwt jwt(Authentication authentication) {
        return (Jwt) authentication.getPrincipal();
    }
}
