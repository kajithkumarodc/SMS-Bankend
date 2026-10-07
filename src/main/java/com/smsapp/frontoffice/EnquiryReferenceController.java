package com.smsapp.frontoffice;

import com.smsapp.frontoffice.EnquiryDtos.CreateEnquiryReferenceRequest;
import com.smsapp.frontoffice.EnquiryDtos.EnquiryReferenceResponse;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Configurable enquiry references. Read = ENQUIRY_VIEW; managing the catalog = ENQUIRY_EDIT. */
@RestController
@RequestMapping("/api/v1/enquiry-references")
public class EnquiryReferenceController {

    private final EnquiryReferenceService referenceService;

    public EnquiryReferenceController(EnquiryReferenceService referenceService) {
        this.referenceService = referenceService;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_ENQUIRY_VIEW)
    List<EnquiryReferenceResponse> list() {
        return referenceService.listActive().stream().map(EnquiryReferenceResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_ENQUIRY_EDIT)
    ResponseEntity<EnquiryReferenceResponse> create(@Valid @RequestBody CreateEnquiryReferenceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(EnquiryReferenceResponse.from(referenceService.create(request.name())));
    }
}
