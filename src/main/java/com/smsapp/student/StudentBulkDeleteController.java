package com.smsapp.student;

import com.smsapp.student.StudentBulkDeleteService.Candidate;
import com.smsapp.student.StudentBulkDeleteService.Result;
import com.smsapp.user.Permissions;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Student Information -> Bulk Delete. Both endpoints need the STUDENT_DELETE permission. */
@RestController
@RequestMapping("/api/v1/students/bulk-delete")
public class StudentBulkDeleteController {

    private final StudentBulkDeleteService service;

    public StudentBulkDeleteController(StudentBulkDeleteService service) {
        this.service = service;
    }

    /** Students of a class (optionally one section), each flagged deletable or with the blocking reason. */
    @GetMapping("/candidates")
    @PreAuthorize(Permissions.HAS_STUDENT_DELETE)
    List<Candidate> candidates(@RequestParam UUID classId, @RequestParam(required = false) UUID sectionId) {
        return service.candidates(classId, sectionId);
    }

    /** Permanently deletes the students that have no dependent history; the rest come back as skipped. */
    @PostMapping
    @PreAuthorize(Permissions.HAS_STUDENT_DELETE)
    Result delete(@RequestBody BulkDeleteRequest request) {
        return service.delete(request.studentIds());
    }

    record BulkDeleteRequest(@NotNull List<UUID> studentIds) {
    }
}
