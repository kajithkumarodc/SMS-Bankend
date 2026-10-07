package com.smsapp.academics;

import com.smsapp.academics.ClassTeacherService.ClassTeacherRow;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Academics > Assign Class Teacher. SCHOOL_ADMIN only. */
@RestController
@RequestMapping("/api/v1/academics/class-teachers")
@PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
public class ClassTeacherController {

    record AssignRequest(@NotNull @Size(max = 20) List<@NotNull UUID> staffProfileIds) {
    }

    private final ClassTeacherService service;

    public ClassTeacherController(ClassTeacherService service) {
        this.service = service;
    }

    @GetMapping
    List<ClassTeacherRow> list() {
        return service.list();
    }

    /** Sets the class teachers of a section (replacing its current ones). */
    @PutMapping("/{sectionId}")
    ClassTeacherRow assign(@PathVariable UUID sectionId, @Valid @RequestBody AssignRequest request) {
        return service.assign(sectionId, request.staffProfileIds());
    }

    @DeleteMapping("/{sectionId}")
    ResponseEntity<Void> remove(@PathVariable UUID sectionId) {
        service.remove(sectionId);
        return ResponseEntity.noContent().build();
    }
}
