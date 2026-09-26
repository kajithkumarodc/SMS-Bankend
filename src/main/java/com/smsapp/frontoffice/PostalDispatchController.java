package com.smsapp.frontoffice;

import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.PostalDispatchDtos.DispatchRequest;
import com.smsapp.frontoffice.PostalDispatchDtos.DispatchResponse;
import com.smsapp.frontoffice.PostalDispatchDtos.DocumentResponse;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Front Office / Postal Dispatch, gated by the POSTAL_DISPATCH_* permissions seeded in V34. */
@RestController
@RequestMapping("/api/v1/postal-dispatches")
public class PostalDispatchController {

    /** Sortable list columns: API sort key -> entity property. */
    private static final Map<String, String> SORTABLE = Map.of(
            "toTitle", "toTitle",
            "referenceNo", "referenceNo",
            "fromTitle", "fromTitle",
            "dispatchDate", "dispatchDate",
            "createdAt", "createdAt");

    private final PostalDispatchService service;

    public PostalDispatchController(PostalDispatchService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_POSTAL_DISPATCH_CREATE)
    ResponseEntity<DispatchResponse> create(@Valid @RequestBody DispatchRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.toResponse(service.create(request, userId(authentication))));
    }

    /**
     * Search/list, paginated. {@code q} matches reference no, titles, address or note. Sort keys are the
     * {@link #SORTABLE} names (400 otherwise); by default and as a tie-break, the latest dispatch comes first.
     */
    @GetMapping
    @PreAuthorize(Permissions.HAS_POSTAL_DISPATCH_VIEW)
    PagedModel<DispatchResponse> list(@RequestParam(required = false) String q,
                                      @PageableDefault(size = 50) Pageable pageable) {
        Page<PostalDispatch> page = service.search(q, withEntitySort(pageable));
        return new PagedModel<>(new PageImpl<>(service.toResponses(page.getContent()), pageable, page.getTotalElements()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_POSTAL_DISPATCH_VIEW)
    DispatchResponse get(@PathVariable UUID id) {
        return service.toResponse(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_POSTAL_DISPATCH_EDIT)
    DispatchResponse update(@PathVariable UUID id, @Valid @RequestBody DispatchRequest request) {
        return service.toResponse(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_POSTAL_DISPATCH_DELETE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Attach one supporting document. Allowed for whoever can add a dispatch (it's part of the Add form) or edit one. */
    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Permissions.HAS_POSTAL_DISPATCH_CREATE + " or " + Permissions.HAS_POSTAL_DISPATCH_EDIT)
    ResponseEntity<DocumentResponse> addDocument(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                                 Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(DocumentResponse.from(service.addDocument(id, file, userId(authentication))));
    }

    @GetMapping("/{id}/documents/{documentId}")
    @PreAuthorize(Permissions.HAS_POSTAL_DISPATCH_VIEW)
    ResponseEntity<Resource> downloadDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        PostalDispatchDocument document = service.getDocument(id, documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(document.getOriginalFilename()).build().toString())
                .body(service.loadDocument(document));
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    @PreAuthorize(Permissions.HAS_POSTAL_DISPATCH_EDIT)
    ResponseEntity<Void> deleteDocument(@PathVariable UUID id, @PathVariable UUID documentId) {
        service.deleteDocument(id, documentId);
        return ResponseEntity.noContent().build();
    }

    private static Pageable withEntitySort(Pageable pageable) {
        Sort sort = Sort.unsorted();
        for (Sort.Order order : pageable.getSort()) {
            String property = SORTABLE.get(order.getProperty());
            if (property == null) {
                throw new ApiException("Can't sort dispatches by '" + order.getProperty() + "'", HttpStatus.BAD_REQUEST);
            }
            sort = sort.and(Sort.by(order.withProperty(property)));
        }
        for (String tieBreak : List.of("dispatchDate", "createdAt")) {
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
