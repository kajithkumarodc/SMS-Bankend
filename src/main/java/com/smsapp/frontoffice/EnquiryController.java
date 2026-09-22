package com.smsapp.frontoffice;

import com.smsapp.frontoffice.EnquiryDtos.ArchiveEnquiryRequest;
import com.smsapp.frontoffice.EnquiryDtos.AssignableStaffResponse;
import com.smsapp.frontoffice.EnquiryDtos.ChangeEnquiryStatusRequest;
import com.smsapp.frontoffice.EnquiryDtos.CreateEnquiryRequest;
import com.smsapp.frontoffice.EnquiryDtos.EnquiryConversionResult;
import com.smsapp.frontoffice.EnquiryDtos.EnquiryResponse;
import com.smsapp.frontoffice.EnquiryDtos.EnquirySummaryResponse;
import com.smsapp.frontoffice.EnquiryDtos.FollowUpResponse;
import com.smsapp.frontoffice.EnquiryDtos.LinkApplicationRequest;
import com.smsapp.frontoffice.EnquiryDtos.RecordFollowUpRequest;
import com.smsapp.frontoffice.EnquiryDtos.UpdateEnquiryRequest;
import com.smsapp.admission.AdmissionApplicationService;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Front Office / Admission Enquiry (Phase 2). The first module built directly
 * against RBAC Phase 1's permission system ({@code hasAuthority(...)}) rather
 * than fixed roles -- SCHOOL_ADMIN/SUPER_ADMIN hold every ENQUIRY_* permission
 * by default (V22/V23 seed), RECEPTIONIST holds the day-to-day subset.
 */
@RestController
@RequestMapping("/api/v1/enquiries")
public class EnquiryController {

    private final EnquiryService enquiryService;
    private final AdmissionApplicationService admissionApplicationService;

    public EnquiryController(EnquiryService enquiryService, AdmissionApplicationService admissionApplicationService) {
        this.enquiryService = enquiryService;
        this.admissionApplicationService = admissionApplicationService;
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_ENQUIRY_CREATE)
    ResponseEntity<EnquiryResponse> create(@Valid @RequestBody CreateEnquiryRequest request) {
        AdmissionEnquiry created = enquiryService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(enquiryService.toResponse(created));
    }

    /**
     * Search/list, paginated. {@code q} matches applicant name, guardian name, phone or enquiry number.
     * Archived enquiries are excluded unless {@code includeArchived=true}.
     */
    @GetMapping
    @PreAuthorize(Permissions.HAS_ENQUIRY_VIEW)
    PagedModel<EnquiryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID sourceId,
            @RequestParam(required = false) UUID classId,
            @RequestParam(required = false) UUID assignedStaffUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        Page<AdmissionEnquiry> page = enquiryService.search(q, normalizedStatus, sourceId, classId,
                assignedStaffUserId, from, to, includeArchived, pageable);
        return new PagedModel<>(page.map(enquiryService::toResponse));
    }

    @GetMapping("/summary")
    @PreAuthorize(Permissions.HAS_ENQUIRY_VIEW)
    EnquirySummaryResponse summary() {
        return enquiryService.summary();
    }

    /** Users who can be assigned an enquiry -- a lightweight picker, not the full Users admin list. */
    @GetMapping("/assignable-staff")
    @PreAuthorize(Permissions.HAS_ENQUIRY_VIEW)
    List<AssignableStaffResponse> assignableStaff() {
        return enquiryService.assignableStaff();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_ENQUIRY_VIEW)
    EnquiryResponse get(@PathVariable UUID id) {
        return enquiryService.toResponse(enquiryService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_ENQUIRY_EDIT)
    EnquiryResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateEnquiryRequest request) {
        return enquiryService.toResponse(enquiryService.update(id, request));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(Permissions.HAS_ENQUIRY_EDIT)
    EnquiryResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeEnquiryStatusRequest request) {
        return enquiryService.toResponse(enquiryService.changeStatus(id, request.status()));
    }

    /** Archive (soft delete) or restore an enquiry. Follow-up history is preserved either way. */
    @PatchMapping("/{id}/archive")
    @PreAuthorize(Permissions.HAS_ENQUIRY_DELETE)
    EnquiryResponse archive(@PathVariable UUID id, @Valid @RequestBody ArchiveEnquiryRequest request) {
        return enquiryService.toResponse(enquiryService.setArchived(id, request.archived()));
    }

    @PostMapping("/{id}/follow-ups")
    @PreAuthorize(Permissions.HAS_ENQUIRY_FOLLOWUP)
    ResponseEntity<FollowUpResponse> recordFollowUp(@PathVariable UUID id,
                                                    @Valid @RequestBody RecordFollowUpRequest request,
                                                    Authentication authentication) {
        EnquiryFollowUp created = enquiryService.recordFollowUp(id, request, userId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(enquiryService.toResponse(created));
    }

    @GetMapping("/{id}/follow-ups")
    @PreAuthorize(Permissions.HAS_ENQUIRY_VIEW)
    List<FollowUpResponse> followUps(@PathVariable UUID id) {
        return enquiryService.listFollowUps(id).stream().map(enquiryService::toResponse).toList();
    }

    /**
     * Converts the enquiry into a real student admission -- the request body is the same
     * {@code CreateStudentRequest} the Students module's own "Add student" form uses (schoolId,
     * admissionNumber, dateOfBirth, etc. are not on the enquiry and must be supplied here).
     * 404 if the enquiry doesn't exist, 409 if it was already converted or the admission
     * number is taken (both surfaced by the underlying student-creation path unchanged).
     */
    @PostMapping("/{id}/convert")
    @PreAuthorize(Permissions.HAS_ENQUIRY_CONVERT)
    ResponseEntity<EnquiryConversionResult> convert(@PathVariable UUID id,
                                                     @Valid @RequestBody CreateStudentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(enquiryService.convertToStudent(id, request));
    }

    /** Links this enquiry to the online application it turned into (plan Phase 4.5 part 17). Informational only. */
    @PostMapping("/{id}/link-application")
    @PreAuthorize(Permissions.HAS_ENQUIRY_EDIT)
    EnquiryResponse linkApplication(@PathVariable UUID id, @Valid @RequestBody LinkApplicationRequest request) {
        admissionApplicationService.requireExists(request.applicationId());
        return enquiryService.toResponse(enquiryService.linkApplication(id, request.applicationId()));
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
