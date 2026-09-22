package com.smsapp.frontoffice;

import com.smsapp.frontoffice.EnquiryDtos.CreateEnquirySourceRequest;
import com.smsapp.frontoffice.EnquiryDtos.EnquirySourceResponse;
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

/** Configurable enquiry sources. Read = ENQUIRY_VIEW; managing the catalog = ENQUIRY_EDIT. */
@RestController
@RequestMapping("/api/v1/enquiry-sources")
public class EnquirySourceController {

    private final EnquirySourceService sourceService;

    public EnquirySourceController(EnquirySourceService sourceService) {
        this.sourceService = sourceService;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_ENQUIRY_VIEW)
    List<EnquirySourceResponse> list() {
        return sourceService.listActive().stream().map(EnquirySourceResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_ENQUIRY_EDIT)
    ResponseEntity<EnquirySourceResponse> create(@Valid @RequestBody CreateEnquirySourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(EnquirySourceResponse.from(sourceService.create(request.name())));
    }
}
