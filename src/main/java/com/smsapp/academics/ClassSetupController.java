package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.ClassResponse;
import com.smsapp.user.Roles;
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

/** Academics > Sections (the master list) and Class (a class with the sections it picks). SCHOOL_ADMIN only. */
@RestController
@RequestMapping("/api/v1/academics")
@PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
public class ClassSetupController {

    record SectionNameRequest(@NotBlank @Size(max = 100) String name) {
    }

    record SectionNameResponse(UUID id, String name) {
        static SectionNameResponse from(SectionName entry) {
            return new SectionNameResponse(entry.getId(), entry.getName());
        }
    }

    record ClassSetupRequest(@NotBlank @Size(max = 100) String name, @Size(max = 50) List<@NotBlank String> sectionNames) {
    }

    private final ClassSetupService service;

    public ClassSetupController(ClassSetupService service) {
        this.service = service;
    }

    @GetMapping("/section-names")
    List<SectionNameResponse> sectionNames() {
        return service.sectionNames().stream().map(SectionNameResponse::from).toList();
    }

    @PostMapping("/section-names")
    ResponseEntity<SectionNameResponse> createSectionName(@Valid @RequestBody SectionNameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(SectionNameResponse.from(service.createSectionName(request.name())));
    }

    @PutMapping("/section-names/{id}")
    SectionNameResponse renameSectionName(@PathVariable UUID id, @Valid @RequestBody SectionNameRequest request) {
        return SectionNameResponse.from(service.renameSectionName(id, request.name()));
    }

    @DeleteMapping("/section-names/{id}")
    ResponseEntity<Void> deleteSectionName(@PathVariable UUID id) {
        service.deleteSectionName(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/classes")
    ResponseEntity<ClassResponse> createClass(@Valid @RequestBody ClassSetupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createClass(request.name(), request.sectionNames()));
    }

    @PutMapping("/classes/{id}")
    ClassResponse updateClass(@PathVariable UUID id, @Valid @RequestBody ClassSetupRequest request) {
        return service.updateClass(id, request.name(), request.sectionNames());
    }

    @DeleteMapping("/classes/{id}")
    ResponseEntity<Void> deleteClass(@PathVariable UUID id) {
        service.deleteClass(id);
        return ResponseEntity.noContent().build();
    }
}
