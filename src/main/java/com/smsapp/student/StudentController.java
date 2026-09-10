package com.smsapp.student;

import com.smsapp.hostel.HostelService;
import com.smsapp.student.StudentDtos.AllocateHostelRoomRequest;
import com.smsapp.student.StudentDtos.AssignSectionRequest;
import com.smsapp.student.StudentDtos.AssignTransportRouteRequest;
import com.smsapp.student.StudentDtos.ChangeStudentStatusRequest;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentDtos.StudentResponse;
import com.smsapp.student.StudentDtos.UpdateStudentRequest;
import com.smsapp.transport.TransportService;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private final StudentService studentService;
    private final TransportService transportService;
    private final HostelService hostelService;

    public StudentController(StudentService studentService, TransportService transportService,
                             HostelService hostelService) {
        this.studentService = studentService;
        this.transportService = transportService;
        this.hostelService = hostelService;
    }

    /** Only SCHOOL_ADMIN may enroll a student (plan section 2). A TEACHER gets 403. */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<StudentResponse> create(@Valid @RequestBody CreateStudentRequest request,
                                           Authentication authentication,
                                           UriComponentsBuilder uriBuilder) {
        Student student = studentService.create(tenantId(authentication), request);
        URI location = uriBuilder.path("/api/v1/students/{id}").buildAndExpand(student.getId()).toUri();
        return ResponseEntity.created(location).body(StudentResponse.from(student));
    }

    /**
     * Lists students for the caller's tenant, paginated. Optionally filtered to one
     * section via {@code ?sectionId=} -- still tenant-scoped underneath.
     */
    @GetMapping
    PagedModel<StudentResponse> list(@RequestParam(required = false) UUID sectionId,
                                     @PageableDefault(size = 20) Pageable pageable,
                                     Authentication authentication) {
        UUID tenantId = tenantId(authentication);
        Page<Student> students = sectionId == null
                ? studentService.list(tenantId, pageable)
                : studentService.listBySection(tenantId, sectionId, pageable);
        return new PagedModel<>(students.map(StudentResponse::from));
    }

    /**
     * SCHOOL_ADMIN or TEACHER only -- this exposes a student's full record (guardian
     * contact included), so a student or parent must use {@code /api/v1/me/student}
     * or {@code /api/v1/me/children}. Returns 404 (not 403) when the student belongs
     * to another tenant -- no existence leak.
     */
    @GetMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    StudentResponse get(@PathVariable UUID id, Authentication authentication) {
        return StudentResponse.from(studentService.get(tenantId(authentication), id));
    }

    /**
     * Updates a student's editable fields. SCHOOL_ADMIN only; a TEACHER gets 403.
     * Cross-tenant ids get 404, same as {@link #get}. {@code admissionNumber} is
     * not editable (see {@link StudentService#update}).
     */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
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
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse changeStatus(@PathVariable UUID id,
                                        @Valid @RequestBody ChangeStudentStatusRequest request,
                                        Authentication authentication) {
        return StudentResponse.from(studentService.changeStatus(tenantId(authentication), id, request.status()));
    }

    /**
     * Assign / reassign a student to a section. SCHOOL_ADMIN only; a TEACHER gets 403.
     * 404 if the student or the section is not in the caller's tenant.
     */
    @PatchMapping("/{id}/section")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse assignSection(@PathVariable UUID id,
                                         @Valid @RequestBody AssignSectionRequest request,
                                         Authentication authentication) {
        return StudentResponse.from(
                studentService.assignSection(tenantId(authentication), id, request.sectionId()));
    }

    /**
     * Assign a student to a transport route, or unassign them with a null
     * {@code routeId}. SCHOOL_ADMIN only; a TEACHER gets 403. 404 if the student or
     * the route is not in the caller's tenant.
     */
    @PatchMapping("/{id}/transport-route")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse assignTransportRoute(@PathVariable UUID id,
                                                @Valid @RequestBody AssignTransportRouteRequest request,
                                                Authentication authentication) {
        return StudentResponse.from(
                transportService.assignStudentRoute(tenantId(authentication), id, request.routeId()));
    }

    /**
     * Allocate a student to a hostel room, or deallocate them with a null
     * {@code roomId}. SCHOOL_ADMIN only; a TEACHER gets 403. 404 if the student or
     * the room is not in the caller's tenant, 400 if the room is already full.
     */
    @PatchMapping("/{id}/hostel-room")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse allocateHostelRoom(@PathVariable UUID id,
                                              @Valid @RequestBody AllocateHostelRoomRequest request,
                                              Authentication authentication) {
        return StudentResponse.from(
                hostelService.allocateStudentRoom(tenantId(authentication), id, request.roomId()));
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
