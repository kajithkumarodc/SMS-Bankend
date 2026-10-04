package com.smsapp.staff;

import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Human Resource > Leave Type, gated by LEAVE_TYPE_MANAGE (V49). */
@RestController
@RequestMapping("/api/v1/hr/leave-types")
public class LeaveTypeController {

    record LeaveTypeRequest(@NotBlank @Size(max = 100) String name) {
    }

    record LeaveTypeResponse(UUID id, String name) {

        static LeaveTypeResponse from(LeaveType type) {
            return new LeaveTypeResponse(type.getId(), type.getName());
        }
    }

    private final LeaveTypeService service;

    public LeaveTypeController(LeaveTypeService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_LEAVE_TYPE_MANAGE)
    List<LeaveTypeResponse> list() {
        return service.list().stream().map(LeaveTypeResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_LEAVE_TYPE_MANAGE)
    ResponseEntity<LeaveTypeResponse> create(@Valid @RequestBody LeaveTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(LeaveTypeResponse.from(service.create(request.name())));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_LEAVE_TYPE_MANAGE)
    LeaveTypeResponse rename(@PathVariable UUID id, @Valid @RequestBody LeaveTypeRequest request) {
        return LeaveTypeResponse.from(service.rename(id, request.name()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_LEAVE_TYPE_MANAGE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
