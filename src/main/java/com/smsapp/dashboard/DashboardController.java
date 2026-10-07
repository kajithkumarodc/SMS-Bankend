package com.smsapp.dashboard;

import com.smsapp.user.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    private final SuperAdminDashboardService superAdminDashboardService;

    public DashboardController(DashboardService dashboardService,
                               SuperAdminDashboardService superAdminDashboardService) {
        this.dashboardService = dashboardService;
        this.superAdminDashboardService = superAdminDashboardService;
    }

    @GetMapping("/super-admin")
    @PreAuthorize("hasRole('" + Roles.SUPER_ADMIN + "')")
    SuperAdminDashboard superAdmin() {
        return superAdminDashboardService.build();
    }

    @GetMapping("/summary")
    DashboardSummary summary(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        List<String> roles = jwt.getClaimAsStringList("roles");

        return dashboardService.summaryFor(jwt.getSubject(), roles == null ? List.of() : roles);
    }
}
