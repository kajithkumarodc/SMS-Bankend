package com.smsapp.staff;

import com.smsapp.staff.LeaveManagementDtos.LeaveBody;
import com.smsapp.staff.LeaveManagementDtos.LeaveResponse;
import com.smsapp.staff.LeaveManagementDtos.MyLeaveInfo;
import com.smsapp.staff.LeaveManagementDtos.OptionsResponse;
import com.smsapp.staff.LeaveManagementDtos.StaffOption;
import com.smsapp.staff.LeaveManagementService.LeaveActor;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Human Resource > Approve Leave Request. The LEAVE_* permissions (seeded in V22, extended in V48) gate the
 * endpoints: LEAVE_VIEW to read, LEAVE_CREATE to add, LEAVE_APPROVE to decide. The approval flow by role (Principal
 * for staff, Super Admin for the Principal, admins for anyone but themselves) is applied in the service.
 */
@RestController
@RequestMapping("/api/v1/hr/leave-requests")
public class LeaveManagementController {

    private final LeaveManagementService service;

    public LeaveManagementController(LeaveManagementService service) {
        this.service = service;
    }

    @GetMapping("/options")
    @PreAuthorize(Permissions.HAS_LEAVE_VIEW)
    OptionsResponse options() {
        return service.options();
    }

    /** The active staff of one role for the Name dropdown: the caller, and the staff they can decide for. */
    @GetMapping("/staff")
    @PreAuthorize(Permissions.HAS_LEAVE_CREATE)
    List<StaffOption> staff(@RequestParam UUID roleId, Authentication authentication) {
        return service.staff(roleId, actor(authentication));
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_LEAVE_VIEW)
    List<LeaveResponse> list(Authentication authentication) {
        return service.list(actor(authentication));
    }

    /** The signed-in user's own requests -- the Apply Leave page. */
    @GetMapping("/mine")
    @PreAuthorize(Permissions.HAS_LEAVE_CREATE)
    List<LeaveResponse> mine(Authentication authentication) {
        return service.mine(actor(authentication));
    }

    /** Who approves the signed-in user's leave and what is left of each leave type this year (or {@code year}). */
    @GetMapping("/my-info")
    @PreAuthorize(Permissions.HAS_LEAVE_CREATE)
    MyLeaveInfo myInfo(@RequestParam(required = false) Integer year, Authentication authentication) {
        return service.myInfo(actor(authentication), year == null ? java.time.LocalDate.now().getYear() : year);
    }

    /** Withdraws the signed-in user's own request while it is Pending. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(Permissions.HAS_LEAVE_CREATE)
    ResponseEntity<Void> cancel(@PathVariable UUID id, Authentication authentication) {
        service.cancel(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_LEAVE_VIEW)
    LeaveResponse get(@PathVariable UUID id, Authentication authentication) {
        return service.get(id, actor(authentication));
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_LEAVE_CREATE)
    ResponseEntity<LeaveResponse> create(@Valid @RequestBody LeaveBody body, Authentication authentication) {
        LeaveActor actor = actor(authentication);
        LeaveRequest created = service.create(body, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.toResponse(created, actor));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_LEAVE_APPROVE)
    LeaveResponse update(@PathVariable UUID id, @Valid @RequestBody LeaveBody body, Authentication authentication) {
        LeaveActor actor = actor(authentication);
        return service.toResponse(service.update(id, body, actor), actor);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_LEAVE_APPROVE)
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PutMapping(value = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_LEAVE_CREATE + " or " + Permissions.HAS_LEAVE_APPROVE)
    LeaveResponse attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file, Authentication authentication) {
        LeaveActor actor = actor(authentication);
        return service.toResponse(service.attach(id, file, actor), actor);
    }

    @DeleteMapping("/{id}/attachment")
    @PreAuthorize(Permissions.HAS_LEAVE_APPROVE)
    LeaveResponse removeAttachment(@PathVariable UUID id, Authentication authentication) {
        LeaveActor actor = actor(authentication);
        return service.toResponse(service.removeAttachment(id, actor), actor);
    }

    @GetMapping("/{id}/attachment")
    @PreAuthorize(Permissions.HAS_LEAVE_VIEW)
    ResponseEntity<Resource> downloadAttachment(@PathVariable UUID id, Authentication authentication) {
        LeaveRequest request = service.requireWithAttachment(id, actor(authentication));
        Resource file = service.loadAttachment(request);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(request.getAttachmentContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(request.getAttachmentOriginalFilename()).build().toString())
                .body(file);
    }

    private static LeaveActor actor(Authentication authentication) {
        UUID userId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        Set<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring("ROLE_".length())).collect(Collectors.toSet());
        return new LeaveActor(userId, roles);
    }
}
