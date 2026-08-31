package com.smsapp.student;

import com.smsapp.student.StudentDtos.ChangeStudentStatusRequest;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentDtos.StudentResponse;
import com.smsapp.student.StudentDtos.UpdateStudentRequest;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    /** Only SCHOOL_ADMIN may enroll a student (plan section 2). A TEACHER gets 403. */
    @PostMapping
    @PreAuthorize("hasRole('" + Roles.SCHOOL_ADMIN + "')")
    public ResponseEntity<StudentResponse> create(@Valid @RequestBody CreateStudentRequest request,
                                           Authentication authentication,
                                           UriComponentsBuilder uriBuilder) {
        Student student = studentService.create(tenantId(authentication), request);
        URI location = uriBuilder.path("/api/v1/students/{id}").buildAndExpand(student.getId()).toUri();
        return ResponseEntity.created(location).body(StudentResponse.from(student));
    }

    /** Lists students for the caller's tenant, paginated. */
    @GetMapping
    PagedModel<StudentResponse> list(@PageableDefault(size = 20) Pageable pageable, Authentication authentication) {
        return new PagedModel<>(studentService.list(tenantId(authentication), pageable).map(StudentResponse::from));
    }

    /** Returns 404 (not 403) when the student belongs to another tenant -- no existence leak. */
    @GetMapping("/{id}")
    StudentResponse get(@PathVariable UUID id, Authentication authentication) {
        return StudentResponse.from(studentService.get(tenantId(authentication), id));
    }

    /**
     * Updates a student's editable fields. SCHOOL_ADMIN only; a TEACHER gets 403.
     * Cross-tenant ids get 404, same as {@link #get}. {@code admissionNumber} is
     * not editable (see {@link StudentService#update}).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('" + Roles.SCHOOL_ADMIN + "')")
    public StudentResponse update(@PathVariable UUID id,
                                  @Valid @RequestBody UpdateStudentRequest request,
                                  Authentication authentication) {
        return StudentResponse.from(studentService.update(tenantId(authentication), id, request));
    }

    /**
     * Deactivate (soft delete) or reactivate a student. SCHOOL_ADMIN only; a TEACHER
     * gets 403. No row is deleted -- the student stays in the historical record.
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('" + Roles.SCHOOL_ADMIN + "')")
    public StudentResponse changeStatus(@PathVariable UUID id,
                                        @Valid @RequestBody ChangeStudentStatusRequest request,
                                        Authentication authentication) {
        return StudentResponse.from(studentService.changeStatus(tenantId(authentication), id, request.status()));
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
