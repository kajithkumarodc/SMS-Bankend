package com.smsapp.transport;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.transport.TransportDtos.CreateRouteRequest;
import com.smsapp.transport.TransportDtos.CreateVehicleRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transport routes, vehicles and student-route assignment (plan section 2). Every
 * read and write is explicitly scoped by {@code tenant_id} on top of the RLS
 * policy. A cross-tenant route / student reference is reported as 404, never 403,
 * so the API never leaks that the row exists.
 */
@Service
public class TransportService {

    private static final String ROUTE_NOT_FOUND = "Route not found";
    private static final String STUDENT_NOT_FOUND = "Student not found";

    private final TransportRouteRepository routeRepository;
    private final TransportVehicleRepository vehicleRepository;
    private final StudentRepository studentRepository;
    private final AuditService auditService;

    public TransportService(TransportRouteRepository routeRepository,
                            TransportVehicleRepository vehicleRepository,
                            StudentRepository studentRepository,
                            AuditService auditService) {
        this.routeRepository = routeRepository;
        this.vehicleRepository = vehicleRepository;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
    }

    // --- Routes --------------------------------------------------

    @Transactional
    public TransportRoute createRoute(UUID tenantId, CreateRouteRequest request) {
        TransportRoute route = new TransportRoute();
        route.setTenantId(tenantId);
        route.setName(request.name().trim());
        TransportRoute saved = routeRepository.save(route);

        auditService.log(AuditActions.TRANSPORT_ROUTE_CREATED, AuditActions.TRANSPORT_ROUTE, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TransportRoute> listRoutes(UUID tenantId) {
        return routeRepository.findByTenantIdOrderByName(tenantId);
    }

    // --- Vehicles -----------------------------------------------

    /**
     * @throws ApiException 404 if {@code routeId} is given but not in the caller's
     *         tenant, 409 if the registration number is already used in the tenant.
     */
    @Transactional
    public TransportVehicle addVehicle(UUID tenantId, CreateVehicleRequest request) {
        String registrationNumber = request.registrationNumber().trim();

        if (request.routeId() != null && !routeRepository.existsByIdAndTenantId(request.routeId(), tenantId)) {
            throw new ApiException(ROUTE_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        if (vehicleRepository.existsByTenantIdAndRegistrationNumber(tenantId, registrationNumber)) {
            throw registrationConflict(registrationNumber);
        }

        TransportVehicle vehicle = new TransportVehicle();
        vehicle.setTenantId(tenantId);
        vehicle.setRouteId(request.routeId());
        vehicle.setRegistrationNumber(registrationNumber);
        vehicle.setDriverName(request.driverName().trim());
        vehicle.setDriverContact(blankToNull(request.driverContact()));
        vehicle.setCapacity(request.capacity());

        TransportVehicle saved;
        try {
            saved = vehicleRepository.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent insert of the same registration number.
            throw registrationConflict(registrationNumber);
        }

        auditService.log(AuditActions.TRANSPORT_VEHICLE_ADDED, AuditActions.TRANSPORT_VEHICLE, saved.getId(),
                details("registrationNumber", saved.getRegistrationNumber(),
                        "routeId", saved.getRouteId() == null ? null : saved.getRouteId().toString()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TransportVehicle> listVehicles(UUID tenantId, UUID routeId) {
        return routeId == null
                ? vehicleRepository.findByTenantIdOrderByRegistrationNumber(tenantId)
                : vehicleRepository.findByTenantIdAndRouteIdOrderByRegistrationNumber(tenantId, routeId);
    }

    // --- Student <-> route -------------------------------------

    /**
     * Assigns (or, with a null {@code routeId}, unassigns) a student's transport route.
     *
     * @throws ApiException 404 if the student is not in the caller's tenant, or if
     *         {@code routeId} is given but not in the caller's tenant.
     */
    @Transactional
    public Student assignStudentRoute(UUID tenantId, UUID studentId, UUID routeId) {
        Student student = studentRepository.findByIdAndTenantId(studentId, tenantId)
                .orElseThrow(() -> new ApiException(STUDENT_NOT_FOUND, HttpStatus.NOT_FOUND));
        if (routeId != null && !routeRepository.existsByIdAndTenantId(routeId, tenantId)) {
            throw new ApiException(ROUTE_NOT_FOUND, HttpStatus.NOT_FOUND);
        }

        UUID previousRouteId = student.getTransportRouteId();
        student.setTransportRouteId(routeId);
        Student saved = studentRepository.save(student);

        auditService.log(AuditActions.TRANSPORT_ROUTE_ASSIGNED, AuditActions.STUDENT, studentId, details(
                "from", previousRouteId == null ? null : previousRouteId.toString(),
                "to", routeId == null ? null : routeId.toString()));
        return saved;
    }

    /**
     * The students assigned to a route, for staff.
     *
     * @throws ApiException 404 if the route is not in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public List<Student> studentsOnRoute(UUID tenantId, UUID routeId) {
        if (!routeRepository.existsByIdAndTenantId(routeId, tenantId)) {
            throw new ApiException(ROUTE_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        return studentRepository.findByTenantIdAndTransportRouteIdOrderByFullName(tenantId, routeId);
    }

    /**
     * A student's transport assignment (route + vehicles + driver info), for the
     * portals. The caller's ownership of the student has already been proven.
     *
     * @throws ApiException 404 if the student has no transport route (or it has since
     *         been removed) -- a clean "not assigned", not an error.
     */
    @Transactional(readOnly = true)
    public TransportAssignment assignmentForRoute(UUID tenantId, UUID routeId) {
        if (routeId == null) {
            throw new ApiException("No transport route is assigned", HttpStatus.NOT_FOUND);
        }
        TransportRoute route = routeRepository.findByIdAndTenantId(routeId, tenantId)
                .orElseThrow(() -> new ApiException("No transport route is assigned", HttpStatus.NOT_FOUND));
        List<TransportAssignment.Vehicle> vehicles = vehicleRepository
                .findByTenantIdAndRouteIdOrderByRegistrationNumber(tenantId, routeId)
                .stream().map(TransportAssignment.Vehicle::from).toList();
        return new TransportAssignment(route.getId(), route.getName(), vehicles);
    }

    private static ApiException registrationConflict(String registrationNumber) {
        return new ApiException(
                "A vehicle with registration number '" + registrationNumber + "' already exists",
                HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Null-tolerant map builder ({@link Map#of} rejects null values). Keys/values alternate. */
    private static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
