package com.smsapp.homework;

import com.smsapp.homework.HomeworkDtos.CreateHomeworkRequest;
import com.smsapp.homework.HomeworkDtos.HomeworkResponse;
import com.smsapp.homework.HomeworkDtos.RecordSubmissionRequest;
import com.smsapp.homework.HomeworkDtos.SubmissionResponse;
import com.smsapp.homework.HomeworkDtos.SubmissionRowResponse;
import com.smsapp.homework.HomeworkDtos.UpdateHomeworkRequest;
import com.smsapp.homework.HomeworkService.SubmissionResult;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Staff-facing homework endpoints. Every method here is SCHOOL_ADMIN or TEACHER --
 * a student or parent reads their own homework through the ownership-scoped
 * {@code /api/v1/me/...} endpoints on {@code PortalController}, never these.
 */
@RestController
@RequestMapping("/api/v1/homework")
public class HomeworkController {

    private final HomeworkService homeworkService;

    public HomeworkController(HomeworkService homeworkService) {
        this.homeworkService = homeworkService;
    }

    /**
     * Set homework for a section. 404 if the class, section or subject does not exist;
     * 400 if the section is not in that class or the due date precedes the assigned date.
     */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    public ResponseEntity<HomeworkResponse> create(@Valid @RequestBody CreateHomeworkRequest request,
                                                   Authentication authentication) {
        Homework created = homeworkService.create(userId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(HomeworkResponse.from(created));
    }

    /**
     * A section's homework, newest first, optionally narrowed to one subject.
     * 404 if the section does not exist.
     */
    @GetMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    PagedModel<HomeworkResponse> list(@RequestParam UUID sectionId,
                                      @RequestParam(required = false) UUID subjectId,
                                      @PageableDefault(size = 20) Pageable pageable) {
        return new PagedModel<>(homeworkService.listForSection(sectionId, subjectId, pageable)
                .map(HomeworkResponse::from));
    }

    /** One piece of homework. 404 if it does not exist. */
    @GetMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    HomeworkResponse get(@PathVariable UUID id) {
        return HomeworkResponse.from(homeworkService.get(id));
    }

    /**
     * Correct the title, description or due date. Class, section and subject are fixed
     * at creation. 404 if it does not exist; 400 if the due date precedes the assigned date.
     */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    HomeworkResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateHomeworkRequest request) {
        return HomeworkResponse.from(homeworkService.update(id, request));
    }

    /** Deletes the homework and every submission against it. 404 if it does not exist. */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        homeworkService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The submission sheet: the whole section roster joined to what has been recorded,
     * so an unmarked student appears as PENDING. 404 if the homework does not exist.
     */
    @GetMapping("/{id}/submissions")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<SubmissionRowResponse> submissions(@PathVariable UUID id) {
        return homeworkService.submissionSheet(id);
    }

    /**
     * Record (or correct) one student's state. Re-recording the same student updates the
     * row (200); a first record returns 201. 404 if the homework or student does not
     * exist; 400 if the status is unknown or the student is not on that section's roster.
     */
    @PostMapping("/{id}/submissions/{studentId}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    public ResponseEntity<SubmissionResponse> recordSubmission(@PathVariable UUID id,
                                                               @PathVariable UUID studentId,
                                                               @Valid @RequestBody RecordSubmissionRequest request,
                                                               Authentication authentication) {
        SubmissionResult result = homeworkService.recordSubmission(userId(authentication), id, studentId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(SubmissionResponse.from(result.submission()));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
