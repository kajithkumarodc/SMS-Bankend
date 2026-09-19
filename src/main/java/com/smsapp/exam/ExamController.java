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
     * class or the subject does not exist.
     */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    public ResponseEntity<ExamResponse> create(@Valid @RequestBody CreateExamRequest request) {
        Exam created = examService.createExam(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExamResponse.from(created));
    }

    /**
     * Exams for one class (most recent first). SCHOOL_ADMIN or TEACHER only -- a student
     * or parent reads results through the ownership-scoped {@code /api/v1/me/...} endpoints.
     * 404 if the class does not exist.
     */
    @GetMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<ExamResponse> listForClass(@RequestParam UUID classId) {
        return examService.listForClass(classId).stream().map(ExamResponse::from).toList();
    }

    /**
     * Record (or correct) a student's marks. SCHOOL_ADMIN or TEACHER. Re-recording the
     * same student updates the row (200); a first entry returns 201. 400 if the marks
     * exceed the exam's max; 404 if the exam or student does not exist.
     */
    @PostMapping("/{examId}/marks")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    public ResponseEntity<ExamMarkResponse> recordMark(@PathVariable UUID examId,
                                                       @Valid @RequestBody RecordMarkRequest request) {
        MarkResult result = examService.recordMark(examId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ExamMarkResponse.from(result.mark()));
    }

    /**
     * The gradebook: every recorded mark for an exam. SCHOOL_ADMIN or TEACHER only --
     * this returns every student's marks, so a student or parent must use the
     * ownership-scoped {@code /api/v1/me/...} results endpoints. 404 if the exam
     * does not exist.
     */
    @GetMapping("/{examId}/marks")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<ExamMarkResponse> gradebook(@PathVariable UUID examId) {
        return examService.gradebook(examId).stream().map(ExamMarkResponse::from).toList();
    }

    /**
     * One student's results across all exams, for staff use. SCHOOL_ADMIN or TEACHER only --
     * a STUDENT or PARENT must use {@code /api/v1/me/student/results} or
     * {@code /api/v1/me/children/{studentId}/results}, which enforce ownership. 404 if the
     * student does not exist.
     */
    @GetMapping("/student/{studentId}")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<StudentExamResultResponse> studentResults(@PathVariable UUID studentId) {
        return examService.studentResults(studentId).stream().map(StudentExamResultResponse::from).toList();
    }
}
