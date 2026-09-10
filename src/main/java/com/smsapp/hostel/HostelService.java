package com.smsapp.hostel;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.hostel.HostelDtos.CreateBlockRequest;
import com.smsapp.hostel.HostelDtos.CreateRoomRequest;
import com.smsapp.hostel.HostelRoomRepository.RoomWithOccupancy;
import com.smsapp.student.Student;
import com.smsapp.student.StudentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hostel blocks, rooms and student allocation (plan section 2). Every read and
 * write is explicitly scoped by {@code tenant_id} on top of the RLS policy. A
 * cross-tenant block / room / student reference is reported as 404, never 403, so
 * the API never leaks that the row exists.
 */
@Service
public class HostelService {

    private static final String BLOCK_NOT_FOUND = "Block not found";
    private static final String ROOM_NOT_FOUND = "Room not found";
    private static final String STUDENT_NOT_FOUND = "Student not found";

    private final HostelBlockRepository blockRepository;
    private final HostelRoomRepository roomRepository;
    private final StudentRepository studentRepository;
    private final AuditService auditService;

    public HostelService(HostelBlockRepository blockRepository, HostelRoomRepository roomRepository,
                         StudentRepository studentRepository, AuditService auditService) {
        this.blockRepository = blockRepository;
        this.roomRepository = roomRepository;
        this.studentRepository = studentRepository;
        this.auditService = auditService;
    }

    // --- Blocks -------------------------------------------------

    @Transactional
    public HostelBlock createBlock(UUID tenantId, CreateBlockRequest request) {
        HostelBlock block = new HostelBlock();
        block.setTenantId(tenantId);
        block.setName(request.name().trim());
        HostelBlock saved = blockRepository.save(block);

        auditService.log(AuditActions.HOSTEL_BLOCK_CREATED, AuditActions.HOSTEL_BLOCK, saved.getId(),
                Map.of("name", saved.getName()));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<HostelBlock> listBlocks(UUID tenantId) {
        return blockRepository.findByTenantIdOrderByName(tenantId);
    }

    // --- Rooms -------------------------------------------------

    /**
     * @throws ApiException 404 if the block is not in the caller's tenant, 409 if the
     *         room number is already used within that block.
     */
    @Transactional
    public HostelRoom addRoom(UUID tenantId, UUID blockId, CreateRoomRequest request) {
        if (!blockRepository.existsByIdAndTenantId(blockId, tenantId)) {
            throw new ApiException(BLOCK_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        String roomNumber = request.roomNumber().trim();
        if (roomRepository.existsByTenantIdAndBlockIdAndRoomNumber(tenantId, blockId, roomNumber)) {
            throw roomNumberConflict(roomNumber);
        }

        HostelRoom room = new HostelRoom();
        room.setTenantId(tenantId);
        room.setBlockId(blockId);
        room.setRoomNumber(roomNumber);
        room.setCapacity(request.capacity());

        HostelRoom saved;
        try {
            saved = roomRepository.saveAndFlush(room);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent insert of the same room number.
            throw roomNumberConflict(roomNumber);
        }

        auditService.log(AuditActions.HOSTEL_ROOM_ADDED, AuditActions.HOSTEL_ROOM, saved.getId(),
                Map.of("blockId", blockId.toString(), "roomNumber", saved.getRoomNumber(),
                        "capacity", saved.getCapacity()));
        return saved;
    }

    /**
     * Rooms of a block, each with its current occupancy.
     *
     * @throws ApiException 404 if the block is not in the caller's tenant.
     */
    @Transactional(readOnly = true)
    public List<RoomWithOccupancy> listRooms(UUID tenantId, UUID blockId) {
        if (!blockRepository.existsByIdAndTenantId(blockId, tenantId)) {
            throw new ApiException(BLOCK_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        return roomRepository.findRoomsWithOccupancy(tenantId, blockId);
    }

    // --- Student <-> room ------------------------------------

    /**
     * Allocates (or, with a null {@code roomId}, deallocates) a student's hostel room.
     *
     * @throws ApiException 404 if the student is not in the caller's tenant, or if
     *         {@code roomId} is given but not in the caller's tenant; 400 if the
     *         target room is already at full capacity.
     */
    @Transactional
    public Student allocateStudentRoom(UUID tenantId, UUID studentId, UUID roomId) {
        Student student = studentRepository.findByIdAndTenantId(studentId, tenantId)
                .orElseThrow(() -> new ApiException(STUDENT_NOT_FOUND, HttpStatus.NOT_FOUND));

        if (roomId != null && !roomId.equals(student.getHostelRoomId())) {
            HostelRoom room = roomRepository.findByIdAndTenantId(roomId, tenantId)
                    .orElseThrow(() -> new ApiException(ROOM_NOT_FOUND, HttpStatus.NOT_FOUND));
            long occupied = studentRepository.countByTenantIdAndHostelRoomId(tenantId, roomId);
            if (occupied >= room.getCapacity()) {
                throw new ApiException("This room is already at full capacity", HttpStatus.BAD_REQUEST);
            }
        }

        UUID previousRoomId = student.getHostelRoomId();
        student.setHostelRoomId(roomId);
        Student saved = studentRepository.save(student);

        auditService.log(AuditActions.HOSTEL_ROOM_ALLOCATED, AuditActions.STUDENT, studentId, details(
                "from", previousRoomId == null ? null : previousRoomId.toString(),
                "to", roomId == null ? null : roomId.toString()));
        return saved;
    }

    /**
     * The students allocated to a room, for staff.
     *
     * @throws ApiException 404 if the room is not in the caller's tenant, or not in
     *         the given block.
     */
    @Transactional(readOnly = true)
    public List<Student> studentsInRoom(UUID tenantId, UUID blockId, UUID roomId) {
        HostelRoom room = roomRepository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new ApiException(ROOM_NOT_FOUND, HttpStatus.NOT_FOUND));
        if (!room.getBlockId().equals(blockId)) {
            throw new ApiException(ROOM_NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        return studentRepository.findByTenantIdAndHostelRoomIdOrderByFullName(tenantId, roomId);
    }

    /**
     * A student's hostel allocation (block + room + roommates), for the portals. The
     * caller's ownership of the student has already been proven.
     *
     * @param studentId the student whose allocation this is -- excluded from the
     *                  roommates list.
     * @throws ApiException 404 if the student has no hostel room (or it has since
     *         been removed) -- a clean "not allocated", not an error.
     */
    @Transactional(readOnly = true)
    public HostelAllocation allocationForRoom(UUID tenantId, UUID studentId, UUID roomId) {
        if (roomId == null) {
            throw new ApiException("No hostel room is allocated", HttpStatus.NOT_FOUND);
        }
        HostelRoom room = roomRepository.findByIdAndTenantId(roomId, tenantId)
                .orElseThrow(() -> new ApiException("No hostel room is allocated", HttpStatus.NOT_FOUND));
        HostelBlock block = blockRepository.findByIdAndTenantId(room.getBlockId(), tenantId)
                .orElseThrow(() -> new ApiException("No hostel room is allocated", HttpStatus.NOT_FOUND));

        List<String> roommates = studentRepository
                .findByTenantIdAndHostelRoomIdOrderByFullName(tenantId, roomId)
                .stream()
                .filter(s -> !s.getId().equals(studentId))
                .map(Student::getFullName)
                .toList();

        return new HostelAllocation(block.getId(), block.getName(), room.getId(), room.getRoomNumber(),
                room.getCapacity(), roommates);
    }

    private static ApiException roomNumberConflict(String roomNumber) {
        return new ApiException(
                "A room numbered '" + roomNumber + "' already exists in this block",
                HttpStatus.CONFLICT);
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
