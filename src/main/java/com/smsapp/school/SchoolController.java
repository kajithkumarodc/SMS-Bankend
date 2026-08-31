package com.smsapp.school;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only directory of the caller's schools. Exists so the frontend can offer
 * a school picker when enrolling a student; a fuller schools/campus module comes
 * later. Any authenticated role in the tenant may read it.
 */
@RestController
@RequestMapping("/api/v1/schools")
public class SchoolController {

    private final SchoolDirectoryService schoolDirectoryService;

    public SchoolController(SchoolDirectoryService schoolDirectoryService) {
        this.schoolDirectoryService = schoolDirectoryService;
    }

    @GetMapping
    public List<SchoolDirectoryService.SchoolSummary> list(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return schoolDirectoryService.listForTenant(UUID.fromString(jwt.getClaimAsString("tenant_id")));
    }
}
