package com.smsapp.admission;

import com.smsapp.admission.AdmissionDtos.AdmissionCycleResponse;
import com.smsapp.admission.AdmissionDtos.CreateAdmissionCycleRequest;
import com.smsapp.admission.AdmissionDtos.UpdateAdmissionCycleRequest;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin management of admission cycles (plan Phase 4.5 part 1/9/19). */
@RestController
@RequestMapping("/api/v1/admission-cycles")
public class AdmissionCycleController {

    private final AdmissionCycleService cycleService;

    public AdmissionCycleController(AdmissionCycleService cycleService) {
        this.cycleService = cycleService;
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_ADMISSION_CYCLE_CREATE)
    ResponseEntity<AdmissionCycleResponse> create(@Valid @RequestBody CreateAdmissionCycleRequest request,
                                                   Authentication authentication) {
        AdmissionCycle created = cycleService.create(request, userId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(cycleService.toResponse(created));
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_ADMISSION_CYCLE_VIEW)
    List<AdmissionCycleResponse> list() {
        return cycleService.list().stream().map(cycleService::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_ADMISSION_CYCLE_VIEW)
    AdmissionCycleResponse get(@PathVariable UUID id) {
        return cycleService.toResponse(cycleService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_ADMISSION_CYCLE_EDIT)
    AdmissionCycleResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateAdmissionCycleRequest request) {
        return cycleService.toResponse(cycleService.update(id, request));
    }

    @PostMapping("/{id}/open")
    @PreAuthorize(Permissions.HAS_ADMISSION_CYCLE_OPEN)
    AdmissionCycleResponse open(@PathVariable UUID id) {
        return cycleService.toResponse(cycleService.open(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(Permissions.HAS_ADMISSION_CYCLE_CLOSE)
    AdmissionCycleResponse close(@PathVariable UUID id) {
        return cycleService.toResponse(cycleService.close(id));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
