package com.smsapp.hostel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every query is explicitly filtered by {@code tenant_id} -- defense in depth on top of RLS. */
public interface HostelRoomRepository extends JpaRepository<HostelRoom, UUID> {

    List<HostelRoom> findByTenantIdAndBlockIdOrderByRoomNumber(UUID tenantId, UUID blockId);

    Optional<HostelRoom> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndBlockIdAndRoomNumber(UUID tenantId, UUID blockId, String roomNumber);

    /** Rooms of a block, each with a live count of the students allocated to it. */
    @Query("select r.id as id, r.blockId as blockId, r.roomNumber as roomNumber, r.capacity as capacity, "
            + "r.createdAt as createdAt, "
            + "(select count(s.id) from com.smsapp.student.Student s "
            + " where s.tenantId = :tenantId and s.hostelRoomId = r.id) as occupied "
            + "from HostelRoom r where r.tenantId = :tenantId and r.blockId = :blockId "
            + "order by r.roomNumber")
    List<RoomWithOccupancy> findRoomsWithOccupancy(@Param("tenantId") UUID tenantId,
                                                   @Param("blockId") UUID blockId);

    interface RoomWithOccupancy {
        UUID getId();

        UUID getBlockId();

        String getRoomNumber();

        int getCapacity();

        OffsetDateTime getCreatedAt();

        long getOccupied();
    }
}
