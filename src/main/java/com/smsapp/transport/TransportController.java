package com.smsapp.transport;

import com.smsapp.transport.TransportDtos.CreateRouteRequest;
import com.smsapp.transport.TransportDtos.CreateVehicleRequest;
import com.smsapp.transport.TransportDtos.RouteResponse;
import com.smsapp.transport.TransportDtos.RouteStudentView;
import com.smsapp.transport.TransportDtos.VehicleResponse;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Transport routes + vehicles (plan section 2). Routes and vehicles are created
 * by a SCHOOL_ADMIN only; the catalogue-style lists are readable by any signed-in
 * user; the per-route student roster is SCHOOL_ADMIN or TEACHER. A student's /
 * parent's own transport assignment is served ownership-scoped under
 * {@code /api/v1/me/...}.
 */
@RestController
@RequestMapping("/api/v1/transport")
public class TransportController {

    private final TransportService transportService;

    public TransportController(TransportService transportService) {
        this.transportService = transportService;
    }

    /** Create a route. SCHOOL_ADMIN only. */
    @PostMapping("/routes")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<RouteResponse> createRoute(@Valid @RequestBody CreateRouteRequest request,
                                                     Authentication authentication) {
        TransportRoute created = transportService.createRoute(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(RouteResponse.from(created));
    }

    /** All routes for the caller's tenant, by name. */
    @GetMapping("/routes")
    List<RouteResponse> listRoutes(Authentication authentication) {
        return transportService.listRoutes(tenantId(authentication)).stream().map(RouteResponse::from).toList();
    }

    /**
     * Add a vehicle. SCHOOL_ADMIN only. Optionally assigned to a route at creation
     * ({@code routeId} in the body). 404 if that route is not in the caller's tenant,
     * 409 if the registration number is already used in the tenant.
     */
    @PostMapping("/vehicles")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<VehicleResponse> addVehicle(@Valid @RequestBody CreateVehicleRequest request,
                                                      Authentication authentication) {
        TransportVehicle created = transportService.addVehicle(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(VehicleResponse.from(created));
    }

    /** Vehicles for the caller's tenant, optionally filtered to one route via {@code ?routeId=}. */
    @GetMapping("/vehicles")
    List<VehicleResponse> listVehicles(@RequestParam(required = false) UUID routeId,
                                       Authentication authentication) {
        return transportService.listVehicles(tenantId(authentication), routeId).stream()
                .map(VehicleResponse::from).toList();
    }

    /**
     * The students assigned to a route. SCHOOL_ADMIN or TEACHER only. 404 if the
     * route is not in the caller's tenant.
     */
    @GetMapping("/routes/{routeId}/students")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<RouteStudentView> studentsOnRoute(@PathVariable UUID routeId, Authentication authentication) {
        return transportService.studentsOnRoute(tenantId(authentication), routeId).stream()
                .map(RouteStudentView::from).toList();
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
