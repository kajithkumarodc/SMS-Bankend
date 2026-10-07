package com.smsapp.academics;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mediums of instruction. Anyone signed in can list them; only SCHOOL_ADMIN edits the list. */
@RestController
@RequestMapping("/api/v1/mediums")
public class MediumController {

    private final MediumRepository mediumRepository;
    private final AuditService auditService;

    public MediumController(MediumRepository mediumRepository, AuditService auditService) {
        this.mediumRepository = mediumRepository;
        this.auditService = auditService;
    }

    record MediumRequest(@NotBlank @Size(max = 100) String name, Boolean active) {
    }

    record MediumResponse(UUID id, String name, boolean active) {
        static MediumResponse from(Medium medium) {
            return new MediumResponse(medium.getId(), medium.getName(), medium.isActive());
        }
    }

    @GetMapping
    List<MediumResponse> list() {
        return mediumRepository.findAllByOrderByNameAsc().stream().map(MediumResponse::from).toList();
    }

    /** 409 if a medium with that name exists. */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    @Transactional
    ResponseEntity<MediumResponse> create(@Valid @RequestBody MediumRequest request) {
        String name = request.name().trim();
        if (mediumRepository.existsByNameIgnoreCase(name)) {
            throw new ApiException("A medium named '" + name + "' already exists", HttpStatus.CONFLICT);
        }
        Medium medium = new Medium();
        medium.setName(name);
        Medium saved = mediumRepository.save(medium);
        auditService.log(AuditActions.MEDIUM_CREATED, AuditActions.MEDIUM, saved.getId(), Map.of("name", name));
        return ResponseEntity.status(HttpStatus.CREATED).body(MediumResponse.from(saved));
    }

    /** Rename and/or (de)activate. 404 if missing, 409 on a duplicate name. */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    @Transactional
    MediumResponse update(@PathVariable UUID id, @Valid @RequestBody MediumRequest request) {
        Medium medium = require(id);
        String name = request.name().trim();
        if (mediumRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ApiException("A medium named '" + name + "' already exists", HttpStatus.CONFLICT);
        }
        String previous = medium.getName();
        medium.setName(name);
        if (request.active() != null) {
            medium.setActive(request.active());
        }
        Medium saved = mediumRepository.save(medium);
        auditService.log(AuditActions.MEDIUM_UPDATED, AuditActions.MEDIUM, id,
                Map.of("from", previous, "to", name, "active", saved.isActive()));
        return MediumResponse.from(saved);
    }

    /** Deletes an unused medium; one still used by students or fees is deactivated instead (200 with the row). */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    @Transactional
    ResponseEntity<MediumResponse> delete(@PathVariable UUID id) {
        Medium medium = require(id);
        if (mediumRepository.countUsages(id) > 0) {
            medium.setActive(false);
            Medium saved = mediumRepository.save(medium);
            auditService.log(AuditActions.MEDIUM_UPDATED, AuditActions.MEDIUM, id,
                    Map.of("name", medium.getName(), "active", false, "reason", "in use"));
            return ResponseEntity.ok(MediumResponse.from(saved));
        }
        mediumRepository.delete(medium);
        auditService.log(AuditActions.MEDIUM_DELETED, AuditActions.MEDIUM, id, Map.of("name", medium.getName()));
        return ResponseEntity.noContent().build();
    }

    private Medium require(UUID id) {
        return mediumRepository.findById(id)
                .orElseThrow(() -> new ApiException("Medium not found", HttpStatus.NOT_FOUND));
    }
}
