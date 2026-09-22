package com.smsapp.admission;

import com.smsapp.admission.AdmissionDtos.AdmissionApplicationDetailResponse;
import com.smsapp.admission.AdmissionDtos.AdmissionApplicationDocumentResponse;
import com.smsapp.admission.AdmissionDtos.AdmissionApplicationSummaryResponse;
import com.smsapp.admission.AdmissionDtos.ApprovalResult;
import com.smsapp.admission.AdmissionDtos.RejectApplicationRequest;
import com.smsapp.admission.AdmissionDtos.ReviewNotesRequest;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Admin review workflow for admission applications (plan Phase 4.5 parts 7/8/9/10/19). */
@RestController
@RequestMapping("/api/v1/admission-applications")
public class AdmissionApplicationController {

    private final AdmissionApplicationService applicationService;
    private final AdmissionApplicationDocumentService documentService;

    public AdmissionApplicationController(AdmissionApplicationService applicationService,
                                           AdmissionApplicationDocumentService documentService) {
        this.applicationService = applicationService;
        this.documentService = documentService;
    }

    /** Server-side paginated list/search (plan part 7): reference, applicant/guardian, status, cycle, class, date range. */
    @GetMapping
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_VIEW)
    PagedModel<AdmissionApplicationSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID admissionCycleId,
            @RequestParam(required = false) UUID applyingClassId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "submittedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        Page<AdmissionApplication> page = applicationService.search(q, normalizedStatus, admissionCycleId,
                applyingClassId, from, to, pageable);
        return new PagedModel<>(page.map(applicationService::toSummaryResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_VIEW)
    AdmissionApplicationDetailResponse get(@PathVariable UUID id) {
        return applicationService.toDetailResponse(applicationService.get(id));
    }

    @PostMapping("/{id}/start-review")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_REVIEW)
    AdmissionApplicationDetailResponse startReview(@PathVariable UUID id, Authentication authentication) {
        return applicationService.toDetailResponse(applicationService.startReview(id, userId(authentication)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_REJECT)
    AdmissionApplicationDetailResponse reject(@PathVariable UUID id, @Valid @RequestBody RejectApplicationRequest request,
                                              Authentication authentication) {
        return applicationService.toDetailResponse(
                applicationService.reject(id, request.notes(), userId(authentication)));
    }

    @PostMapping("/{id}/waitlist")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_WAITLIST)
    AdmissionApplicationDetailResponse waitlist(@PathVariable UUID id, @RequestBody(required = false) ReviewNotesRequest request,
                                                Authentication authentication) {
        String notes = request != null ? request.notes() : null;
        return applicationService.toDetailResponse(applicationService.waitlist(id, notes, userId(authentication)));
    }

    /** Explicit, audited reopen back into review (plan part 9) -- never a silent status flip. */
    @PostMapping("/{id}/reopen")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_REVIEW)
    AdmissionApplicationDetailResponse reopen(@PathVariable UUID id, @RequestBody(required = false) ReviewNotesRequest request,
                                              Authentication authentication) {
        String notes = request != null ? request.notes() : null;
        return applicationService.toDetailResponse(applicationService.reopen(id, notes, userId(authentication)));
    }

    /**
     * Approval -- creates/links the Student, guardian User and Portal relationship in one transaction
     * (plan parts 10/11/12) and returns exactly what was created so the admin UI can show it.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_APPROVE)
    ApprovalResult approve(@PathVariable UUID id, @RequestBody(required = false) ReviewNotesRequest request,
                           Authentication authentication) {
        String notes = request != null ? request.notes() : null;
        return applicationService.approve(id, notes, userId(authentication));
    }

    // --- Documents ---------------------------------------------------------

    @GetMapping("/{id}/documents")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_DOCUMENT_VIEW)
    List<AdmissionApplicationDocumentResponse> documents(@PathVariable UUID id) {
        return documentService.list(id).stream().map(AdmissionApplicationDocumentResponse::from).toList();
    }

    @PostMapping(value = "/{id}/documents", consumes = "multipart/form-data")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_EDIT)
    ResponseEntity<AdmissionApplicationDocumentResponse> uploadDocument(@PathVariable UUID id,
                                                                        @RequestPart("file") MultipartFile file,
                                                                        @RequestPart("documentType") String documentType,
                                                                        @RequestPart(value = "notes", required = false) String notes) {
        AdmissionApplicationDocument document = documentService.upload(id, documentType, file, notes);
        return ResponseEntity.status(HttpStatus.CREATED).body(AdmissionApplicationDocumentResponse.from(document));
    }

    @GetMapping("/{id}/documents/{documentId}/download")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_DOCUMENT_DOWNLOAD)
    ResponseEntity<Resource> downloadDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        AdmissionApplicationDocument metadata = documentService.get(id, documentId);
        Resource file = documentService.load(id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(metadata.getOriginalFilename()).build().toString())
                .body(file);
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @PreAuthorize(Permissions.HAS_ADMISSION_APPLICATION_EDIT)
    ResponseEntity<Void> deleteDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        documentService.delete(id, documentId);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
