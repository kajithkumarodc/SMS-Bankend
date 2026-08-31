package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.ClassResponse;
import com.smsapp.academics.AcademicsDtos.CreateClassRequest;
import com.smsapp.academics.AcademicsDtos.CreateSectionRequest;
import com.smsapp.academics.AcademicsDtos.SectionResponse;
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

@RestController
@RequestMapping("/api/v1/classes")
public class ClassController {

    private final ClassService classService;

    public ClassController(ClassService classService) {
        this.classService = classService;
    }

    /** Create a class. SCHOOL_ADMIN only; a TEACHER gets 403. */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<ClassResponse> createClass(@Valid @RequestBody CreateClassRequest request,
                                                     Authentication authentication) {
        SchoolClass created = classService.createClass(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClassResponse(created.getId(), created.getSchoolId(), created.getName(), List.of()));
    }

    /** Lists the caller's tenant's classes with their sections nested. */
    @GetMapping
    List<ClassResponse> list(Authentication authentication) {
        return classService.listWithSections(tenantId(authentication));
    }

    /** Create a section under a class. SCHOOL_ADMIN only; a TEACHER gets 403. 404 if the class is another tenant's. */
    @PostMapping("/{classId}/sections")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<SectionResponse> createSection(@PathVariable UUID classId,
                                                         @Valid @RequestBody CreateSectionRequest request,
                                                         Authentication authentication) {
        Section created = classService.createSection(tenantId(authentication), classId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(SectionResponse.from(created));
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
