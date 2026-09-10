package com.smsapp.transport;

import java.util.List;
import java.util.UUID;

/**
 * A student's transport assignment: the route they are on plus the vehicle(s)
 * running it, with driver contact info. Built for the student / parent portals
 * (returned via {@code PortalDtos.TransportView}); carries no internal ids
 * beyond the already-public {@code routeId}.
 */
public record TransportAssignment(
        UUID routeId,
        String routeName,
        List<Vehicle> vehicles) {

    public record Vehicle(
            String registrationNumber,
            String driverName,
            String driverContact,
            int capacity) {

        static Vehicle from(TransportVehicle vehicle) {
            return new Vehicle(vehicle.getRegistrationNumber(), vehicle.getDriverName(),
                    vehicle.getDriverContact(), vehicle.getCapacity());
        }
    }
}
