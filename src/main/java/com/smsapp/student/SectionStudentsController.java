package com.smsapp.student;

import com.smsapp.student.StudentDtos.StudentResponse;
import com.smsapp.user.Roles;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The roster for a section -- its students -- for building the attendance-marking
 * and gradebook screens. SCHOOL_ADMIN or TEACHER only (those screens are staff-only);
 * a student or parent uses {@code /api/v1/me/...}. 404 if the section is not in the
 * caller's tenant (no existence leak).
 */
@RestController
@RequestMapping("/api/v1/sections")
public class SectionStudentsController {

    private final StudentService studentService;

    public SectionStudentsController(StudentService studentService) {
        this.studentService = studentService;
    }

    @GetMapping("/{sectionId}/students")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    PagedModel<StudentResponse> studentsInSection(@PathVariable UUID sectionId,
                                                  @PageableDefault(size = 50) Pageable pageable,
                                                  Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        UUID tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        return new PagedModel<>(
                studentService.listInSection(tenantId, sectionId, pageable).map(StudentResponse::from));
    }
}
