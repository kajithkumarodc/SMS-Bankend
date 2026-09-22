package com.smsapp.academics;

import com.smsapp.academics.TeacherAssignmentService.AssignmentView;
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

import java.util.List;
import java.util.UUID;

/**
 * Teacher self-service views under {@code /api/v1/teacher} (plan Phase 4 section C). Same
 * ownership-scoping convention as {@code PortalController}: every read is confined to this
 * teacher's own assignments via {@link TeacherAssignmentService}, and an id outside that scope
 * is reported as 404, never 403.
 */
@RestController
@RequestMapping("/api/v1/teacher")
@PreAuthorize(Roles.HAS_TEACHER)
public class TeacherController {

    private final TeacherAssignmentService teacherAssignmentService;

    public TeacherController(TeacherAssignmentService teacherAssignmentService) {
        this.teacherAssignmentService = teacherAssignmentService;
    }

    /** The classes/sections/subjects this teacher is assigned to teach. */
    @GetMapping("/assignments")
    public List<AssignmentView> assignments(Authentication authentication) {
        return teacherAssignmentService.myAssignments(userId(authentication));
    }

    /** Every student across this teacher's assigned sections. */
    @GetMapping("/students")
    public PagedModel<StudentResponse> students(@PageableDefault(size = 50) Pageable pageable,
                                                Authentication authentication) {
        return new PagedModel<>(teacherAssignmentService.myStudents(userId(authentication), pageable));
    }

    /** One student, only if they fall within this teacher's assigned sections. 404 otherwise. */
    @GetMapping("/students/{studentId}")
    public StudentResponse student(@PathVariable UUID studentId, Authentication authentication) {
        return teacherAssignmentService.myStudent(userId(authentication), studentId);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
