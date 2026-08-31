package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.CreateSubjectRequest;
import com.smsapp.academics.AcademicsDtos.SubjectResponse;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

    private final SubjectService subjectService;

    public SubjectController(SubjectService subjectService) {
        this.subjectService = subjectService;
    }

    /** Create a subject. SCHOOL_ADMIN only; a TEACHER gets 403. 409 on a duplicate name within the school. */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<SubjectResponse> create(@Valid @RequestBody CreateSubjectRequest request,
                                                  Authentication authentication) {
        Subject created = subjectService.create(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(SubjectResponse.from(created));
    }

    /** Lists all subjects for the caller's tenant. */
    @GetMapping
    List<SubjectResponse> list(Authentication authentication) {
        return subjectService.list(tenantId(authentication)).stream().map(SubjectResponse::from).toList();
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
