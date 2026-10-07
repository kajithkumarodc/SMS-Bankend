package com.smsapp.frontoffice;

import com.smsapp.frontoffice.VisitorDtos.CreatePurposeRequest;
import com.smsapp.frontoffice.VisitorDtos.PurposeResponse;
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

/**
 * Configurable Front Office purposes (Visitor Book). Read = VISITOR_VIEW; adding one = VISITOR_EDIT until
 * Setup Front Office gets its own permission.
 */
@RestController
@RequestMapping("/api/v1/front-office-purposes")
public class FrontOfficePurposeController {

    private final FrontOfficePurposeService purposeService;

    public FrontOfficePurposeController(FrontOfficePurposeService purposeService) {
        this.purposeService = purposeService;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_VISITOR_VIEW)
    List<PurposeResponse> list() {
        return purposeService.listActive().stream().map(PurposeResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_VISITOR_EDIT)
    ResponseEntity<PurposeResponse> create(@Valid @RequestBody CreatePurposeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(PurposeResponse.from(purposeService.create(request.name())));
    }
}
