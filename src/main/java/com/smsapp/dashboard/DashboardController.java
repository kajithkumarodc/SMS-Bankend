package com.smsapp.dashboard;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    DashboardSummary summary(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        List<String> roles = jwt.getClaimAsStringList("roles");

        return dashboardService.summaryFor(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                jwt.getSubject(),
                roles == null ? List.of() : roles);
    }
}
