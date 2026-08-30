package com.smsapp.user;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    @GetMapping
    Map<String, Object> currentUser(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return Map.of(
                "userId", jwt.getSubject(),
                "tenantId", jwt.getClaimAsString("tenant_id"),
                "roles", jwt.getClaimAsStringList("roles"));
    }
}
