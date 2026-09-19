package com.smsapp.hostel;

import com.smsapp.hostel.HostelDtos.BlockResponse;
import com.smsapp.hostel.HostelDtos.CreateBlockRequest;
import com.smsapp.hostel.HostelDtos.CreateRoomRequest;
import com.smsapp.hostel.HostelDtos.RoomResponse;
import com.smsapp.hostel.HostelDtos.RoomStudentView;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Hostel blocks + rooms (plan section 2). Blocks and rooms are created by a
 * SCHOOL_ADMIN only; the lists are readable by any signed-in user; the per-room
 * student roster is SCHOOL_ADMIN or TEACHER. A student's / parent's own
 * allocation is served ownership-scoped under {@code /api/v1/me/...}.
 */
@RestController
@RequestMapping("/api/v1/hostel")
public class HostelController {

    private final HostelService hostelService;

    public HostelController(HostelService hostelService) {
        this.hostelService = hostelService;
    }

    /** Create a block. SCHOOL_ADMIN only. */
    @PostMapping("/blocks")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<BlockResponse> createBlock(@Valid @RequestBody CreateBlockRequest request) {
        HostelBlock created = hostelService.createBlock(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BlockResponse.from(created));
    }

    /** All blocks, by name. */
    @GetMapping("/blocks")
    List<BlockResponse> listBlocks() {
        return hostelService.listBlocks().stream().map(BlockResponse::from).toList();
    }

    /**
     * Add a room to a block. SCHOOL_ADMIN only. 404 if the block doesn't exist,
     * 409 if the room number is already used within that block.
     */
    @PostMapping("/blocks/{blockId}/rooms")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<RoomResponse> addRoom(@PathVariable UUID blockId,
                                                @Valid @RequestBody CreateRoomRequest request) {
        HostelRoom created = hostelService.addRoom(blockId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(RoomResponse.from(created, 0));
    }

    /** Rooms in a block with their current occupancy. 404 if the block doesn't exist. */
    @GetMapping("/blocks/{blockId}/rooms")
    List<RoomResponse> listRooms(@PathVariable UUID blockId) {
        return hostelService.listRooms(blockId).stream().map(RoomResponse::from).toList();
    }

    /**
     * The students allocated to a room. SCHOOL_ADMIN or TEACHER only. 404 if the
     * room doesn't exist or not in the given block.
     */
    @GetMapping("/blocks/{blockId}/rooms/{roomId}/students")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<RoomStudentView> studentsInRoom(@PathVariable UUID blockId, @PathVariable UUID roomId) {
        return hostelService.studentsInRoom(blockId, roomId).stream().map(RoomStudentView::from).toList();
    }
}
