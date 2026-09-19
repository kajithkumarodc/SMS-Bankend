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
                                           UriComponentsBuilder uriBuilder) {
        Student student = studentService.create(request);
        URI location = uriBuilder.path("/api/v1/students/{id}").buildAndExpand(student.getId()).toUri();
        return ResponseEntity.created(location).body(StudentResponse.from(student));
    }

    /**
     * Lists students, paginated. Optionally filtered to one section via
     * {@code ?sectionId=}.
     */
    @GetMapping
    PagedModel<StudentResponse> list(@RequestParam(required = false) UUID sectionId,
                                     @PageableDefault(size = 20) Pageable pageable) {
        Page<Student> students = sectionId == null
                ? studentService.list(pageable)
                : studentService.listBySection(sectionId, pageable);
        return new PagedModel<>(students.map(StudentResponse::from));
    }

    /**
     * SCHOOL_ADMIN or TEACHER only -- this exposes a student's full record (guardian
     * contact included), so a student or parent must use {@code /api/v1/me/student}
     * or {@code /api/v1/me/children}. Returns 404 (not 403) when the student does
     * not exist -- no existence leak.
     */
    @GetMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    StudentResponse get(@PathVariable UUID id) {
        return StudentResponse.from(studentService.get(id));
    }

    /**
     * Updates a student's editable fields. SCHOOL_ADMIN only; a TEACHER gets 403.
     * A nonexistent id gets 404, same as {@link #get}. {@code admissionNumber} is
     * not editable (see {@link StudentService#update}).
     */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateStudentRequest request) {
        return StudentResponse.from(studentService.update(id, request));
    }

    /**
     * Deactivate (soft delete) or reactivate a student. SCHOOL_ADMIN only; a TEACHER
     * gets 403. No row is deleted -- the student stays in the historical record.
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStudentStatusRequest request) {
        return StudentResponse.from(studentService.changeStatus(id, request.status()));
    }

    /**
     * Assign / reassign a student to a section. SCHOOL_ADMIN only; a TEACHER gets 403.
     * 404 if the student or the section does not exist.
     */
    @PatchMapping("/{id}/section")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse assignSection(@PathVariable UUID id, @Valid @RequestBody AssignSectionRequest request) {
        return StudentResponse.from(studentService.assignSection(id, request.sectionId()));
    }

    /**
     * Assign a student to a transport route, or unassign them with a null
     * {@code routeId}. SCHOOL_ADMIN only; a TEACHER gets 403. 404 if the student or
     * the route does not exist.
     */
    @PatchMapping("/{id}/transport-route")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse assignTransportRoute(@PathVariable UUID id,
                                                @Valid @RequestBody AssignTransportRouteRequest request) {
        return StudentResponse.from(transportService.assignStudentRoute(id, request.routeId()));
    }

    /**
     * Allocate a student to a hostel room, or deallocate them with a null
     * {@code roomId}. SCHOOL_ADMIN only; a TEACHER gets 403. 404 if the student or
     * the room does not exist, 400 if the room is already full.
     */
    @PatchMapping("/{id}/hostel-room")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public StudentResponse allocateHostelRoom(@PathVariable UUID id,
                                              @Valid @RequestBody AllocateHostelRoomRequest request) {
        return StudentResponse.from(hostelService.allocateStudentRoom(id, request.roomId()));
    }
}
