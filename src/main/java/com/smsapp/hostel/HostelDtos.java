package com.smsapp.hostel;

import com.smsapp.hostel.HostelRoomRepository.RoomWithOccupancy;
import com.smsapp.student.Student;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the hostel API. Entities are never exposed directly (plan section 7.1d). */
final class HostelDtos {

    private HostelDtos() {
    }

    record CreateBlockRequest(
            @NotBlank @Size(max = 200) String name) {
    }

    record BlockResponse(
            UUID id,
            String name,
            OffsetDateTime createdAt) {

        static BlockResponse from(HostelBlock block) {
            return new BlockResponse(block.getId(), block.getName(), block.getCreatedAt());
        }
    }

    record CreateRoomRequest(
            @NotBlank @Size(max = 30) String roomNumber,
            @NotNull @Min(1) Integer capacity) {
    }

    /** A room with its live occupancy ({@code occupied} of {@code capacity}). */
    record RoomResponse(
            UUID id,
            UUID blockId,
            String roomNumber,
            int capacity,
            long occupied,
            OffsetDateTime createdAt) {

        static RoomResponse from(HostelRoom room, long occupied) {
            return new RoomResponse(room.getId(), room.getBlockId(), room.getRoomNumber(),
                    room.getCapacity(), occupied, room.getCreatedAt());
        }

        static RoomResponse from(RoomWithOccupancy room) {
            return new RoomResponse(room.getId(), room.getBlockId(), room.getRoomNumber(),
                    room.getCapacity(), room.getOccupied(), room.getCreatedAt());
        }
    }

    /** Body for {@code PATCH /api/v1/students/{id}/hostel-room}. A null {@code roomId} deallocates the student. */
    record AllocateHostelRoomRequest(
            UUID roomId) {
    }

    /** One student in a room -- a light view for the staff room roster (not the full record). */
    record RoomStudentView(
            UUID id,
            String fullName,
            String admissionNumber,
            UUID sectionId) {

        static RoomStudentView from(Student student) {
            return new RoomStudentView(student.getId(), student.getFullName(), student.getAdmissionNumber(),
                    student.getSectionId());
        }
    }
}
