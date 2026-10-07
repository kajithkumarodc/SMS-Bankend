package com.smsapp.frontoffice;

import com.smsapp.frontoffice.ComplaintDtos.ComplaintTypeResponse;
import com.smsapp.frontoffice.ComplaintDtos.CreateComplaintTypeRequest;
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
 * Configurable complaint types. Read = COMPLAINT_VIEW; adding one = COMPLAINT_EDIT until Setup Front Office
 * gets its own permission.
 */
@RestController
@RequestMapping("/api/v1/complaint-types")
public class ComplaintTypeController {

    private final ComplaintTypeService complaintTypeService;

    public ComplaintTypeController(ComplaintTypeService complaintTypeService) {
        this.complaintTypeService = complaintTypeService;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_COMPLAINT_VIEW)
    List<ComplaintTypeResponse> list() {
        return complaintTypeService.listActive().stream().map(ComplaintTypeResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_COMPLAINT_EDIT)
    ResponseEntity<ComplaintTypeResponse> create(@Valid @RequestBody CreateComplaintTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ComplaintTypeResponse.from(complaintTypeService.create(request.name())));
    }
}
