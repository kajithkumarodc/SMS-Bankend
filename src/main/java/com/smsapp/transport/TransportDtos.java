package com.smsapp.transport;

import com.smsapp.student.Student;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the transport API. Entities are never exposed directly (plan section 7.1d). */
final class TransportDtos {

    private TransportDtos() {
    }

    record CreateRouteRequest(
            @NotBlank @Size(max = 200) String name) {
    }

    record RouteResponse(
            UUID id,
            String name,
            OffsetDateTime createdAt) {

        static RouteResponse from(TransportRoute route) {
            return new RouteResponse(route.getId(), route.getName(), route.getCreatedAt());
        }
    }

    /** {@code routeId} is optional -- a vehicle may be added unassigned and put on a route later. */
    record CreateVehicleRequest(
            @NotBlank @Size(max = 30) String registrationNumber,
            @NotBlank @Size(max = 200) String driverName,
            @Size(max = 50) String driverContact,
            @NotNull @Min(1) Integer capacity,
            UUID routeId) {
    }

    record VehicleResponse(
            UUID id,
            UUID routeId,
            String registrationNumber,
            String driverName,
            String driverContact,
            int capacity,
            OffsetDateTime createdAt) {

        static VehicleResponse from(TransportVehicle vehicle) {
            return new VehicleResponse(vehicle.getId(), vehicle.getRouteId(), vehicle.getRegistrationNumber(),
                    vehicle.getDriverName(), vehicle.getDriverContact(), vehicle.getCapacity(),
                    vehicle.getCreatedAt());
        }
    }

    /** Body for {@code PATCH /api/v1/students/{id}/transport-route}. A null {@code routeId} unassigns the student. */
    record AssignTransportRouteRequest(
            UUID routeId) {
    }

    /** One student on a route -- a light view for the staff route roster (not the full record). */
    record RouteStudentView(
            UUID id,
            String fullName,
            String admissionNumber,
            UUID sectionId) {

        static RouteStudentView from(Student student) {
            return new RouteStudentView(student.getId(), student.getFullName(), student.getAdmissionNumber(),
                    student.getSectionId());
        }
    }
}
