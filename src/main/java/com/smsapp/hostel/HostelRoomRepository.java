package com.smsapp.hostel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface HostelRoomRepository extends JpaRepository<HostelRoom, UUID> {

    boolean existsByBlockIdAndRoomNumber(UUID blockId, String roomNumber);

    /** Rooms of a block, each with a live count of the students allocated to it. */
    @Query("select r.id as id, r.blockId as blockId, r.roomNumber as roomNumber, r.capacity as capacity, "
            + "r.createdAt as createdAt, "
            + "(select count(s.id) from com.smsapp.student.Student s "
            + " where s.hostelRoomId = r.id) as occupied "
            + "from HostelRoom r where r.blockId = :blockId "
            + "order by r.roomNumber")
    List<RoomWithOccupancy> findRoomsWithOccupancy(@Param("blockId") UUID blockId);

    interface RoomWithOccupancy {
        UUID getId();

        UUID getBlockId();

        String getRoomNumber();

        int getCapacity();

        OffsetDateTime getCreatedAt();

        long getOccupied();
    }
}
