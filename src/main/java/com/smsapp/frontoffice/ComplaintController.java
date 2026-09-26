package com.smsapp.frontoffice;

import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.ComplaintDtos.ComplaintRequest;
import com.smsapp.frontoffice.ComplaintDtos.ComplaintResponse;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/** Front Office / Complaints, gated by the COMPLAINT_* permissions seeded in V36. */
@RestController
@RequestMapping("/api/v1/complaints")
public class ComplaintController {

    /** Sortable list columns: API sort key -> entity property. */
    private static final Map<String, String> SORTABLE = Map.of(
            "complaintNo", "complaintNo",
            "complaintTypeName", "complaintType.name",
            "complainBy", "complainBy",
            "phone", "phone",
            "complaintDate", "complaintDate");

    private final ComplaintService service;

    public ComplaintController(ComplaintService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_COMPLAINT_CREATE)
    ResponseEntity<ComplaintResponse> create(@Valid @RequestBody ComplaintRequest request, Authentication authentication) {
        UUID userId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.toResponse(service.create(request, userId)));
    }

    /**
     * Search/list, paginated. {@code q} matches the text fields, or a complaint number ("417" / "#417"). Sort keys
     * are the {@link #SORTABLE} names (400 otherwise); by default the newest complaint (highest number) is first.
     */
    @GetMapping
    @PreAuthorize(Permissions.HAS_COMPLAINT_VIEW)
    PagedModel<ComplaintResponse> list(@RequestParam(required = false) String q,
                                       @PageableDefault(size = 50) Pageable pageable) {
        Page<Complaint> page = service.search(q, withEntitySort(pageable));
        return new PagedModel<>(new PageImpl<>(service.toResponses(page.getContent()), pageable, page.getTotalElements()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_COMPLAINT_VIEW)
    ComplaintResponse get(@PathVariable UUID id) {
        return service.toResponse(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_COMPLAINT_EDIT)
    ComplaintResponse update(@PathVariable UUID id, @Valid @RequestBody ComplaintRequest request) {
        return service.toResponse(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_COMPLAINT_DELETE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Attach or replace the document. Allowed for whoever can add a complaint (it's part of the Add form) or edit one. */
    @PutMapping(value = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_COMPLAINT_CREATE + " or " + Permissions.HAS_COMPLAINT_EDIT)
    ComplaintResponse attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return service.toResponse(service.attach(id, file));
    }

    @DeleteMapping("/{id}/attachment")
    @PreAuthorize(Permissions.HAS_COMPLAINT_EDIT)
    ComplaintResponse removeAttachment(@PathVariable UUID id) {
        return service.toResponse(service.removeAttachment(id));
    }

    @GetMapping("/{id}/attachment")
    @PreAuthorize(Permissions.HAS_COMPLAINT_VIEW)
    ResponseEntity<Resource> downloadAttachment(@PathVariable UUID id) {
        Complaint complaint = service.get(id);
        Resource file = service.loadAttachment(complaint);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(complaint.getAttachmentContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(complaint.getAttachmentOriginalFilename()).build().toString())
                .body(file);
    }

    private static Pageable withEntitySort(Pageable pageable) {
        Sort sort = Sort.unsorted();
        for (Sort.Order order : pageable.getSort()) {
            String property = SORTABLE.get(order.getProperty());
            if (property == null) {
                throw new ApiException("Can't sort complaints by '" + order.getProperty() + "'", HttpStatus.BAD_REQUEST);
            }
            sort = sort.and(Sort.by(order.withProperty(property)));
        }
        if (sort.getOrderFor("complaintNo") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "complaintNo"));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
