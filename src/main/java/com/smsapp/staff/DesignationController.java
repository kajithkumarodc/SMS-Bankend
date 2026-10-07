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

/** Human Resource > Designation, gated by DESIGNATION_MANAGE (V51). */
@RestController
@RequestMapping("/api/v1/hr/designations")
public class DesignationController {

    record DesignationRequest(@NotBlank @Size(max = 100) String name) {
    }

    record DesignationResponse(UUID id, String name) {
        static DesignationResponse from(Designation designation) {
            return new DesignationResponse(designation.getId(), designation.getName());
        }
    }

    private final DesignationService service;

    public DesignationController(DesignationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_DESIGNATION_MANAGE)
    List<DesignationResponse> list() {
        return service.list().stream().map(DesignationResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_DESIGNATION_MANAGE)
    ResponseEntity<DesignationResponse> create(@Valid @RequestBody DesignationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DesignationResponse.from(service.create(request.name())));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_DESIGNATION_MANAGE)
    DesignationResponse rename(@PathVariable UUID id, @Valid @RequestBody DesignationRequest request) {
        return DesignationResponse.from(service.rename(id, request.name()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_DESIGNATION_MANAGE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
