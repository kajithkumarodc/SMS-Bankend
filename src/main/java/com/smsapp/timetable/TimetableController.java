package com.smsapp.timetable;

import com.smsapp.academics.Subject;
import com.smsapp.timetable.TimetableDtos.PeriodResponse;
import com.smsapp.timetable.TimetableDtos.SubjectGroupRequest;
import com.smsapp.timetable.TimetableDtos.SubjectGroupResponse;
import com.smsapp.timetable.TimetableDtos.SubjectRequest;
import com.smsapp.timetable.TimetableDtos.SubjectResponse;
import com.smsapp.timetable.TimetableDtos.TimetableSaveRequest;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Academics: TIMETABLE_VIEW reads subjects, subject groups and timetables; TIMETABLE_MANAGE saves a timetable;
 * SUBJECT_MANAGE changes subjects and subject groups.
 */
@RestController
@RequestMapping("/api/v1/academics")
public class TimetableController {

    private final AcademicsSubjectService subjects;
    private final SubjectGroupService groups;
    private final TimetableService timetable;

    public TimetableController(AcademicsSubjectService subjects, SubjectGroupService groups, TimetableService timetable) {
        this.subjects = subjects;
        this.groups = groups;
        this.timetable = timetable;
    }

    // --- Subjects -------------------------------------------------------------------------------

    @GetMapping("/subjects")
    @PreAuthorize(Permissions.HAS_TIMETABLE_VIEW + " or " + Permissions.HAS_SUBJECT_MANAGE)
    List<SubjectResponse> subjects() {
        return subjects.list().stream().map(TimetableController::toResponse).toList();
    }

    @PostMapping("/subjects")
    @PreAuthorize(Permissions.HAS_SUBJECT_MANAGE)
    ResponseEntity<SubjectResponse> createSubject(@Valid @RequestBody SubjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(subjects.create(request)));
    }

    @PutMapping("/subjects/{id}")
    @PreAuthorize(Permissions.HAS_SUBJECT_MANAGE)
    SubjectResponse updateSubject(@PathVariable UUID id, @Valid @RequestBody SubjectRequest request) {
        return toResponse(subjects.update(id, request));
    }

    @DeleteMapping("/subjects/{id}")
    @PreAuthorize(Permissions.HAS_SUBJECT_MANAGE)
    ResponseEntity<Void> deleteSubject(@PathVariable UUID id) {
        subjects.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Subject groups -------------------------------------------------------------------------

    @GetMapping("/subject-groups")
    @PreAuthorize(Permissions.HAS_TIMETABLE_VIEW + " or " + Permissions.HAS_SUBJECT_MANAGE)
    List<SubjectGroupResponse> subjectGroups(@RequestParam(required = false) UUID sectionId) {
        return groups.list(sectionId);
    }

    @PostMapping("/subject-groups")
    @PreAuthorize(Permissions.HAS_SUBJECT_MANAGE)
    ResponseEntity<SubjectGroupResponse> createGroup(@Valid @RequestBody SubjectGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groups.create(request));
    }

    @PutMapping("/subject-groups/{id}")
    @PreAuthorize(Permissions.HAS_SUBJECT_MANAGE)
    SubjectGroupResponse updateGroup(@PathVariable UUID id, @Valid @RequestBody SubjectGroupRequest request) {
        return groups.update(id, request);
    }

    @DeleteMapping("/subject-groups/{id}")
    @PreAuthorize(Permissions.HAS_SUBJECT_MANAGE)
    ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        groups.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Timetable ------------------------------------------------------------------------------

    /** A section's whole week, or one subject group's periods of it. */
    @GetMapping("/timetable")
    @PreAuthorize(Permissions.HAS_TIMETABLE_VIEW)
    List<PeriodResponse> periods(@RequestParam UUID sectionId, @RequestParam(required = false) UUID subjectGroupId) {
        return timetable.periods(sectionId, subjectGroupId);
    }

    /** One teacher's periods across all sections. */
    @GetMapping("/timetable/teacher/{staffProfileId}")
    @PreAuthorize(Permissions.HAS_TIMETABLE_VIEW)
    List<PeriodResponse> teacherPeriods(@PathVariable UUID staffProfileId) {
        return timetable.teacherPeriods(staffProfileId);
    }

    @PutMapping("/timetable")
    @PreAuthorize(Permissions.HAS_TIMETABLE_MANAGE)
    List<PeriodResponse> save(@Valid @RequestBody TimetableSaveRequest request) {
        return timetable.save(request);
    }

    private static SubjectResponse toResponse(Subject subject) {
        return new SubjectResponse(subject.getId(), subject.getName(), subject.getCode(), subject.getSubjectType());
    }
}
