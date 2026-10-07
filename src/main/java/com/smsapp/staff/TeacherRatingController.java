package com.smsapp.staff;

import com.smsapp.staff.TeacherRatingDtos.RatableTeacher;
import com.smsapp.staff.TeacherRatingDtos.RatingRow;
import com.smsapp.staff.TeacherRatingDtos.RatingSummary;
import com.smsapp.staff.TeacherRatingDtos.SubmitRatingRequest;
import com.smsapp.user.Permissions;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Human Resource > Teachers Rating. The school reads ratings with TEACHER_RATING_VIEW and approves or deletes them with
 * TEACHER_RATING_MANAGE (V50). Students rate their teachers under {@code /api/v1/me/teacher-ratings}.
 */
@RestController
@RequestMapping("/api/v1")
public class TeacherRatingController {

    private final TeacherRatingService service;

    public TeacherRatingController(TeacherRatingService service) {
        this.service = service;
    }

    @GetMapping("/teacher-ratings")
    @PreAuthorize(Permissions.HAS_TEACHER_RATING_VIEW)
    List<RatingRow> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @PostMapping("/teacher-ratings/{id}/approve")
    @PreAuthorize(Permissions.HAS_TEACHER_RATING_MANAGE)
    ResponseEntity<Void> approve(@PathVariable UUID id, Authentication authentication) {
        service.approve(id, userId(authentication));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/teacher-ratings/{id}")
    @PreAuthorize(Permissions.HAS_TEACHER_RATING_MANAGE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A teacher's average over approved ratings -- shown on their staff profile. */
    @GetMapping("/teacher-ratings/summary/{staffProfileId}")
    @PreAuthorize(Permissions.HAS_STAFF_VIEW + " or " + Permissions.HAS_TEACHER_RATING_VIEW)
    RatingSummary summary(@PathVariable UUID staffProfileId) {
        return service.summary(staffProfileId);
    }

    // --- Students ---------------------------------------------------------------------------------------

    @GetMapping("/me/teacher-ratings")
    @PreAuthorize(Roles.HAS_STUDENT)
    List<RatableTeacher> teachers(Authentication authentication) {
        return service.teachersFor(userId(authentication));
    }

    @PostMapping("/me/teacher-ratings")
    @PreAuthorize(Roles.HAS_STUDENT)
    ResponseEntity<Void> submit(@Valid @RequestBody SubmitRatingRequest request, Authentication authentication) {
        service.submit(userId(authentication), request.staffProfileId(), request.rating(), request.comment());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
