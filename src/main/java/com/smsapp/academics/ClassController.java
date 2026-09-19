package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.AssignSubjectRequest;
import com.smsapp.academics.AcademicsDtos.ClassResponse;
import com.smsapp.academics.AcademicsDtos.CreateClassRequest;
import com.smsapp.academics.AcademicsDtos.CreateSectionRequest;
import com.smsapp.academics.AcademicsDtos.SectionResponse;
import com.smsapp.academics.AcademicsDtos.SubjectResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/classes")
public class ClassController {

    private final ClassService classService;
    private final SubjectService subjectService;

    public ClassController(ClassService classService, SubjectService subjectService) {
        this.classService = classService;
        this.subjectService = subjectService;
    }

    /** Create a class. SCHOOL_ADMIN only; a TEACHER gets 403. */
    @PostMapping
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<ClassResponse> createClass(@Valid @RequestBody CreateClassRequest request) {
        SchoolClass created = classService.createClass(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClassResponse(created.getId(), created.getSchoolId(), created.getName(), List.of()));
    }

    /** Lists all classes with their sections nested. */
    @GetMapping
    List<ClassResponse> list() {
        return classService.listWithSections();
    }

    /** Create a section under a class. SCHOOL_ADMIN only; a TEACHER gets 403. 404 if the class doesn't exist. */
    @PostMapping("/{classId}/sections")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<SectionResponse> createSection(@PathVariable UUID classId,
                                                         @Valid @RequestBody CreateSectionRequest request) {
        Section created = classService.createSection(classId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(SectionResponse.from(created));
    }

    /**
     * Assign an existing subject to a class. SCHOOL_ADMIN only; a TEACHER gets 403.
     * 404 if the class or the subject doesn't exist, 409 if already assigned.
     */
    @PostMapping("/{classId}/subjects")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<SubjectResponse> assignSubject(@PathVariable UUID classId,
                                                         @Valid @RequestBody AssignSubjectRequest request) {
        Subject assigned = subjectService.assignToClass(classId, request.subjectId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SubjectResponse.from(assigned));
    }

    /** Lists the subjects assigned to a class. 404 if the class doesn't exist. */
    @GetMapping("/{classId}/subjects")
    List<SubjectResponse> classSubjects(@PathVariable UUID classId) {
        return subjectService.listForClass(classId).stream().map(SubjectResponse::from).toList();
    }
}
