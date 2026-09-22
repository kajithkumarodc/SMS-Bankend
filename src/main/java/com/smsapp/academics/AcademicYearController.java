package com.smsapp.academics;

import com.smsapp.academics.AcademicsDtos.AcademicYearResponse;
import com.smsapp.academics.AcademicsDtos.CreateAcademicYearRequest;
import com.smsapp.academics.AcademicsDtos.PromoteStudentsRequest;
import com.smsapp.academics.AcademicsDtos.PromoteStudentsResponse;
import com.smsapp.academics.AcademicsDtos.PromotionHistoryEntry;
import com.smsapp.academics.AcademicsDtos.PromotionResultResponse;
import com.smsapp.user.Permissions;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Academic sessions/years + student promotion. SCHOOL_ADMIN/SUPER_ADMIN only, except where overridden below. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize(Roles.HAS_ADMIN)
public class AcademicYearController {

    private final AcademicYearService academicYearService;

    public AcademicYearController(AcademicYearService academicYearService) {
        this.academicYearService = academicYearService;
    }

    @GetMapping("/academic-years")
    java.util.List<AcademicYearResponse> list() {
        return academicYearService.listAll().stream().map(AcademicYearResponse::from).toList();
    }

    /** Whichever year is current, for any authenticated role (parent/teacher/student dashboards all need this). */
    @GetMapping("/academic-years/current")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<AcademicYearResponse> current() {
        return academicYearService.current()
                .map(year -> ResponseEntity.ok(AcademicYearResponse.from(year)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/academic-years")
    ResponseEntity<AcademicYearResponse> create(@Valid @RequestBody CreateAcademicYearRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(AcademicYearResponse.from(academicYearService.create(request)));
    }

    @PatchMapping("/academic-years/{id}/set-current")
    AcademicYearResponse setCurrent(@PathVariable UUID id) {
        return AcademicYearResponse.from(academicYearService.setCurrent(id));
    }

    /** Bulk-moves students from one section to another. 404 if either section doesn't exist. Requires STUDENT_PROMOTE. */
    @PostMapping("/students/promote")
    @PreAuthorize(Permissions.HAS_STUDENT_PROMOTE)
    PromoteStudentsResponse promote(@Valid @RequestBody PromoteStudentsRequest request) {
        AcademicYearService.PromotionOutcome outcome = academicYearService.promoteStudents(
                request.fromSectionId(), request.toSectionId(), request.toClassId(),
                request.targetAcademicYearId(), request.studentIds());
        return new PromoteStudentsResponse(
                outcome.results().stream().map(PromotionResultResponse::from).toList(),
                outcome.promotedCount(), request.studentIds().size());
    }

    /** Promotion History screen (plan Phase 4 section A): every promotion school-wide, newest first. */
    @GetMapping("/academic-years/promotion-history")
    PagedModel<PromotionHistoryEntry> promotionHistory(Pageable pageable) {
        return new PagedModel<>(academicYearService.promotionHistory(pageable));
    }
}
