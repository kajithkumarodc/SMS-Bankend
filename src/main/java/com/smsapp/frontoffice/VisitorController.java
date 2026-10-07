package com.smsapp.frontoffice;

import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.VisitorDtos.MeetingPerson;
import com.smsapp.frontoffice.VisitorDtos.VisitorRequest;
import com.smsapp.frontoffice.VisitorDtos.VisitorResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Front Office / Visitor Book, permission-gated by the VISITOR_* permissions seeded in V32. */
@RestController
@RequestMapping("/api/v1/visitors")
public class VisitorController {

    /** Sortable list columns: API sort key -> entity property. */
    private static final Map<String, String> SORTABLE = Map.of(
            "purposeName", "purpose.name",
            "visitorName", "visitorName",
            "phone", "phone",
            "idCard", "idCard",
            "numberOfPersons", "numberOfPersons",
            "visitDate", "visitDate",
            "inTime", "inTime",
            "outTime", "outTime",
            "createdAt", "createdAt");

    private final VisitorService visitorService;

    public VisitorController(VisitorService visitorService) {
        this.visitorService = visitorService;
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_VISITOR_CREATE)
    ResponseEntity<VisitorResponse> create(@Valid @RequestBody VisitorRequest request, Authentication authentication) {
        Visitor created = visitorService.create(request, userId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(visitorService.toResponse(created));
    }

    /**
     * Search/list, paginated. {@code q} matches visitor name, phone, ID card or note; {@code from}/{@code to}
     * bound the visit date. Sort keys are the {@link #SORTABLE} names (400 otherwise); by default and as a
     * tie-break, the latest visit comes first.
     */
    @GetMapping
    @PreAuthorize(Permissions.HAS_VISITOR_VIEW)
    PagedModel<VisitorResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 50) Pageable pageable) {
        Page<Visitor> page = visitorService.search(q, from, to, withEntitySort(pageable));
        return new PagedModel<>(new PageImpl<>(visitorService.toResponses(page.getContent()), pageable,
                page.getTotalElements()));
    }

    /** Staff members or students to pick from for "Meeting With". {@code type} is STAFF or STUDENT. */
    @GetMapping("/meeting-options")
    @PreAuthorize(Permissions.HAS_VISITOR_VIEW)
    List<MeetingPerson> meetingOptions(@RequestParam String type, @RequestParam(required = false) String q) {
        return visitorService.meetingOptions(type, q);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_VISITOR_VIEW)
    VisitorResponse get(@PathVariable UUID id) {
        return visitorService.toResponse(visitorService.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_VISITOR_EDIT)
    VisitorResponse update(@PathVariable UUID id, @Valid @RequestBody VisitorRequest request) {
        return visitorService.toResponse(visitorService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_VISITOR_DELETE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        visitorService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Attach or replace the document. Allowed for whoever can add a visitor (it's part of the Add form) or edit one. */
    @PutMapping(value = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_VISITOR_CREATE + " or " + Permissions.HAS_VISITOR_EDIT)
    VisitorResponse attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return visitorService.toResponse(visitorService.attach(id, file));
    }

    @DeleteMapping("/{id}/attachment")
    @PreAuthorize(Permissions.HAS_VISITOR_EDIT)
    VisitorResponse removeAttachment(@PathVariable UUID id) {
        return visitorService.toResponse(visitorService.removeAttachment(id));
    }

    @GetMapping("/{id}/attachment")
    @PreAuthorize(Permissions.HAS_VISITOR_VIEW)
    ResponseEntity<Resource> downloadAttachment(@PathVariable UUID id) {
        Visitor visitor = visitorService.get(id);
        Resource file = visitorService.loadAttachment(visitor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(visitor.getAttachmentContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(visitor.getAttachmentOriginalFilename()).build().toString())
                .body(file);
    }

    private static Pageable withEntitySort(Pageable pageable) {
        Sort sort = Sort.unsorted();
        for (Sort.Order order : pageable.getSort()) {
            String property = SORTABLE.get(order.getProperty());
            if (property == null) {
                throw new ApiException("Can't sort visitors by '" + order.getProperty() + "'", HttpStatus.BAD_REQUEST);
            }
            sort = sort.and(Sort.by(order.withProperty(property)));
        }
        for (String tieBreak : List.of("visitDate", "inTime", "createdAt")) {
            if (sort.getOrderFor(tieBreak) == null) {
                sort = sort.and(Sort.by(Sort.Direction.DESC, tieBreak));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
