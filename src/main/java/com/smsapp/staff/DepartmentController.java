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

/** Human Resource > Department, gated by DEPARTMENT_MANAGE (V51). */
@RestController
@RequestMapping("/api/v1/hr/departments")
public class DepartmentController {

    record DepartmentRequest(@NotBlank @Size(max = 100) String name) {
    }

    record DepartmentResponse(UUID id, String name) {
        static DepartmentResponse from(Department department) {
            return new DepartmentResponse(department.getId(), department.getName());
        }
    }

    private final DepartmentService service;

    public DepartmentController(DepartmentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_DEPARTMENT_MANAGE)
    List<DepartmentResponse> list() {
        return service.list().stream().map(DepartmentResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_DEPARTMENT_MANAGE)
    ResponseEntity<DepartmentResponse> create(@Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(DepartmentResponse.from(service.create(request.name())));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_DEPARTMENT_MANAGE)
    DepartmentResponse rename(@PathVariable UUID id, @Valid @RequestBody DepartmentRequest request) {
        return DepartmentResponse.from(service.rename(id, request.name()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_DEPARTMENT_MANAGE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
