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
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public ResponseEntity<BlockResponse> createBlock(@Valid @RequestBody CreateBlockRequest request,
                                                     Authentication authentication) {
        HostelBlock created = hostelService.createBlock(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(BlockResponse.from(created));
    }

    /** All blocks for the caller's tenant, by name. */
    @GetMapping("/blocks")
    List<BlockResponse> listBlocks(Authentication authentication) {
        return hostelService.listBlocks(tenantId(authentication)).stream().map(BlockResponse::from).toList();
    }

    /**
     * Add a room to a block. SCHOOL_ADMIN only. 404 if the block is not in the
     * caller's tenant, 409 if the room number is already used within that block.
     */
    @PostMapping("/blocks/{blockId}/rooms")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<RoomResponse> addRoom(@PathVariable UUID blockId,
                                                @Valid @RequestBody CreateRoomRequest request,
                                                Authentication authentication) {
        HostelRoom created = hostelService.addRoom(tenantId(authentication), blockId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(RoomResponse.from(created, 0));
    }

    /** Rooms in a block with their current occupancy. 404 if the block is not in the caller's tenant. */
    @GetMapping("/blocks/{blockId}/rooms")
    List<RoomResponse> listRooms(@PathVariable UUID blockId, Authentication authentication) {
        return hostelService.listRooms(tenantId(authentication), blockId).stream()
                .map(RoomResponse::from).toList();
    }

    /**
     * The students allocated to a room. SCHOOL_ADMIN or TEACHER only. 404 if the
     * room is not in the caller's tenant or not in the given block.
     */
    @GetMapping("/blocks/{blockId}/rooms/{roomId}/students")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<RoomStudentView> studentsInRoom(@PathVariable UUID blockId, @PathVariable UUID roomId,
                                         Authentication authentication) {
        return hostelService.studentsInRoom(tenantId(authentication), blockId, roomId).stream()
                .map(RoomStudentView::from).toList();
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
