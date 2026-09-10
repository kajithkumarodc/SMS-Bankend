package com.smsapp.hostel;

import java.util.List;
import java.util.UUID;

/**
 * A student's hostel allocation: the block + room they are in, plus their
 * roommates' names (the other students allocated to the same room). Built for the
 * student / parent portals (returned via {@code PortalDtos.HostelView}); carries
 * no internal ids beyond the already-staff-visible block / room ids.
 */
public record HostelAllocation(
        UUID blockId,
        String blockName,
        UUID roomId,
        String roomNumber,
        int capacity,
        List<String> roommates) {
}
