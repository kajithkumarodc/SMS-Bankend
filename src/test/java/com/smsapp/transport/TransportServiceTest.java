package com.smsapp.transport;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import com.smsapp.transport.TransportDtos.CreateRouteRequest;
import com.smsapp.transport.TransportDtos.CreateVehicleRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransportServiceTest {

    @Mock
    private TransportRouteRepository routeRepository;

    @Mock
    private TransportVehicleRepository vehicleRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private AuditService auditService;

    private TransportService service() {
        return new TransportService(routeRepository, vehicleRepository, studentRepository, auditService);
    }

    private final UUID routeId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    private TransportRoute route() {
        TransportRoute route = new TransportRoute();
        route.setId(routeId);
        route.setName("Route 1 - North Zone");
        return route;
    }

    // --- routes -------------------------------------------------

    @Test
    void createRouteTrimsTheNameAndAudits() {
        when(routeRepository.save(any(TransportRoute.class))).thenAnswer(inv -> {
            TransportRoute r = inv.getArgument(0);
            r.setId(routeId);
            return r;
        });

        TransportRoute created = service().createRoute(new CreateRouteRequest("  Route 1 - North Zone  "));

        assertThat(created.getName()).isEqualTo("Route 1 - North Zone");
        verify(auditService).log(eq(AuditActions.TRANSPORT_ROUTE_CREATED), eq(AuditActions.TRANSPORT_ROUTE),
                eq(routeId), anyMap());
    }

    // --- vehicles ----------------------------------------------

    @Test
    void addVehicleStoresTheFieldsTreatsBlankContactAsNullAndAudits() {
        when(routeRepository.existsById(routeId)).thenReturn(true);
        when(vehicleRepository.existsByRegistrationNumber("KA01AB1234")).thenReturn(false);
        when(vehicleRepository.saveAndFlush(any(TransportVehicle.class))).thenAnswer(inv -> {
            TransportVehicle v = inv.getArgument(0);
            v.setId(UUID.randomUUID());
            return v;
        });

        TransportVehicle created = service().addVehicle(
                new CreateVehicleRequest("  KA01AB1234 ", "  Ravi Kumar ", "   ", 40, routeId));

        assertThat(created.getRegistrationNumber()).isEqualTo("KA01AB1234");
        assertThat(created.getDriverName()).isEqualTo("Ravi Kumar");
        assertThat(created.getDriverContact()).isNull();
        assertThat(created.getCapacity()).isEqualTo(40);
        assertThat(created.getRouteId()).isEqualTo(routeId);
        verify(auditService).log(eq(AuditActions.TRANSPORT_VEHICLE_ADDED), eq(AuditActions.TRANSPORT_VEHICLE),
                any(), anyMap());
    }

    @Test
    void addVehicleWithANonexistentRouteReturns404() {
        when(routeRepository.existsById(routeId)).thenReturn(false);

        assertThatThrownBy(() -> service().addVehicle(
                new CreateVehicleRequest("KA01AB1234", "Ravi", null, 40, routeId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(vehicleRepository, never()).saveAndFlush(any());
    }

    @Test
    void addVehicleWithADuplicateRegistrationNumberReturns409() {
        when(vehicleRepository.existsByRegistrationNumber("KA01AB1234")).thenReturn(true);

        assertThatThrownBy(() -> service().addVehicle(new CreateVehicleRequest("KA01AB1234", "Ravi", null, 40, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(vehicleRepository, never()).saveAndFlush(any());
    }

    @Test
    void addVehicleTranslatesAConcurrentInsertRaceIntoAClean409() {
        when(vehicleRepository.existsByRegistrationNumber("KA01AB1234")).thenReturn(false);
        when(vehicleRepository.saveAndFlush(any(TransportVehicle.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service().addVehicle(new CreateVehicleRequest("KA01AB1234", "Ravi", null, 40, null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void listVehiclesFiltersByRouteWhenGivenOtherwiseListsAll() {
        when(vehicleRepository.findAllByOrderByRegistrationNumber()).thenReturn(List.of());
        when(vehicleRepository.findByRouteIdOrderByRegistrationNumber(routeId)).thenReturn(List.of());

        service().listVehicles(null);
        service().listVehicles(routeId);

        verify(vehicleRepository).findAllByOrderByRegistrationNumber();
        verify(vehicleRepository).findByRouteIdOrderByRegistrationNumber(routeId);
    }

    // --- student <-> route ------------------------------------

    @Test
    void assignStudentRouteSetsTheRouteAndAudits() {
        Student student = new Student();
        student.setId(studentId);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(routeRepository.existsById(routeId)).thenReturn(true);
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student saved = service().assignStudentRoute(studentId, routeId);

        assertThat(saved.getTransportRouteId()).isEqualTo(routeId);
        verify(auditService).log(eq(AuditActions.TRANSPORT_ROUTE_ASSIGNED), eq(AuditActions.STUDENT),
                eq(studentId), anyMap());
    }

    @Test
    void assignStudentRouteWithANullRouteUnassignsWithoutCheckingAnyRoute() {
        Student student = new Student();
        student.setId(studentId);
        student.setTransportRouteId(routeId);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        Student saved = service().assignStudentRoute(studentId, null);

        assertThat(saved.getTransportRouteId()).isNull();
        verify(routeRepository, never()).existsById(any());
    }

    @Test
    void assignStudentRouteWithANonexistentStudentReturns404() {
        when(studentRepository.findById(studentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignStudentRoute(studentId, routeId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void assignStudentRouteWithANonexistentRouteReturns404() {
        Student student = new Student();
        student.setId(studentId);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(routeRepository.existsById(routeId)).thenReturn(false);

        assertThatThrownBy(() -> service().assignStudentRoute(studentId, routeId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).save(any());
    }

    @Test
    void studentsOnRouteRejectsANonexistentRouteWith404() {
        when(routeRepository.existsById(routeId)).thenReturn(false);

        assertThatThrownBy(() -> service().studentsOnRoute(routeId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(studentRepository, never()).findByTransportRouteIdOrderByFullName(any());
    }

    // --- assignment view --------------------------------------

    @Test
    void assignmentForRouteIs404WhenTheStudentHasNoRoute() {
        assertThatThrownBy(() -> service().assignmentForRoute(null))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignmentForRouteIs404WhenTheRouteDoesNotExist() {
        when(routeRepository.findById(routeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignmentForRoute(routeId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignmentForRouteBuildsTheRoutePlusItsVehicles() {
        TransportVehicle vehicle = new TransportVehicle();
        vehicle.setRegistrationNumber("KA01AB1234");
        vehicle.setDriverName("Ravi Kumar");
        vehicle.setDriverContact("+91 90000 00000");
        vehicle.setCapacity(40);
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route()));
        when(vehicleRepository.findByRouteIdOrderByRegistrationNumber(routeId)).thenReturn(List.of(vehicle));

        TransportAssignment assignment = service().assignmentForRoute(routeId);

        assertThat(assignment.routeId()).isEqualTo(routeId);
        assertThat(assignment.routeName()).isEqualTo("Route 1 - North Zone");
        assertThat(assignment.vehicles()).singleElement().satisfies(v -> {
            assertThat(v.registrationNumber()).isEqualTo("KA01AB1234");
            assertThat(v.driverName()).isEqualTo("Ravi Kumar");
            assertThat(v.capacity()).isEqualTo(40);
        });
    }
}
