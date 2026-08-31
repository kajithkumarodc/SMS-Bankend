package com.smsapp.exam;

import com.smsapp.exam.ExamDtos.CreateExamRequest;
import com.smsapp.exam.ExamDtos.ExamMarkResponse;
import com.smsapp.exam.ExamDtos.ExamResponse;
import com.smsapp.exam.ExamDtos.RecordMarkRequest;
import com.smsapp.exam.ExamDtos.StudentExamResultResponse;
import com.smsapp.exam.ExamService.MarkResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/exams")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    /**
     * Schedule an exam for a class + subject. SCHOOL_ADMIN or TEACHER. 404 if the
     * class or the subject is not in the caller's tenant.
     */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    public ResponseEntity<ExamResponse> create(@Valid @RequestBody CreateExamRequest request,
                                               Authentication authentication) {
        Exam created = examService.createExam(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExamResponse.from(created));
    }

    /** Exams for one class (most recent first). 404 if the class is not in the caller's tenant. */
    @GetMapping
    List<ExamResponse> listForClass(@RequestParam UUID classId, Authentication authentication) {
        return examService.listForClass(tenantId(authentication), classId).stream()
                .map(ExamResponse::from).toList();
    }

    /**
     * Record (or correct) a student's marks. SCHOOL_ADMIN or TEACHER. Re-recording the
     * same student updates the row (200); a first entry returns 201. 400 if the marks
     * exceed the exam's max; 404 if the exam or student is not in the caller's tenant.
     */
    @PostMapping("/{examId}/marks")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    public ResponseEntity<ExamMarkResponse> recordMark(@PathVariable UUID examId,
                                                       @Valid @RequestBody RecordMarkRequest request,
                                                       Authentication authentication) {
        MarkResult result = examService.recordMark(tenantId(authentication), examId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ExamMarkResponse.from(result.mark()));
    }

    /** The gradebook: every recorded mark for an exam. 404 if the exam is not in the caller's tenant. */
    @GetMapping("/{examId}/marks")
    List<ExamMarkResponse> gradebook(@PathVariable UUID examId, Authentication authentication) {
        return examService.gradebook(tenantId(authentication), examId).stream()
                .map(ExamMarkResponse::from).toList();
    }

    /** One student's results across all exams. 404 if the student is not in the caller's tenant. */
    @GetMapping("/student/{studentId}")
    List<StudentExamResultResponse> studentResults(@PathVariable UUID studentId, Authentication authentication) {
        return examService.studentResults(tenantId(authentication), studentId).stream()
                .map(StudentExamResultResponse::from).toList();
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
