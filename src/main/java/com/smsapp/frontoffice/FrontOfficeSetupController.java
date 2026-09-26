package com.smsapp.frontoffice;

import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.FrontOfficeSetupDtos.DeleteResult;
import com.smsapp.frontoffice.FrontOfficeSetupDtos.SetupItem;
import com.smsapp.frontoffice.FrontOfficeSetupDtos.SetupItemRequest;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Setup Front Office: {@code /api/v1/front-office-setup/{list}} where {@code list} is {@code purposes},
 * {@code complaint-types}, {@code sources} or {@code references}. Everything needs FRONT_OFFICE_SETUP; the
 * forms read these lists through their own module endpoints and VIEW permissions.
 */
@RestController
@RequestMapping("/api/v1/front-office-setup/{list}")
@PreAuthorize(Permissions.HAS_FRONT_OFFICE_SETUP)
public class FrontOfficeSetupController {

    private final FrontOfficeSetupService service;

    public FrontOfficeSetupController(FrontOfficeSetupService service) {
        this.service = service;
    }

    @GetMapping
    List<SetupItem> list(@PathVariable String list) {
        return service.list(resolve(list));
    }

    @PostMapping
    ResponseEntity<SetupItem> create(@PathVariable String list, @Valid @RequestBody SetupItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(resolve(list), request));
    }

    @PutMapping("/{id}")
    SetupItem update(@PathVariable String list, @PathVariable UUID id, @Valid @RequestBody SetupItemRequest request) {
        return service.update(resolve(list), id, request);
    }

    @DeleteMapping("/{id}")
    DeleteResult delete(@PathVariable String list, @PathVariable UUID id) {
        return service.delete(resolve(list), id);
    }

    private static FrontOfficeSetupList resolve(String path) {
        return FrontOfficeSetupList.fromPath(path)
                .orElseThrow(() -> new ApiException("Unknown list '" + path + "'", HttpStatus.NOT_FOUND));
    }
}
