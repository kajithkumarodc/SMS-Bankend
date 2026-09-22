package com.smsapp.fee;

import com.smsapp.fee.FeeDtos.CreateFeeTypeRequest;
import com.smsapp.fee.FeeDtos.FeeTypeResponse;
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

/** Configurable fee types. Read = FEE_VIEW; managing the catalog = FEE_EDIT. */
@RestController
@RequestMapping("/api/v1/fee-types")
public class FeeTypeController {

    private final FeeTypeService feeTypeService;

    public FeeTypeController(FeeTypeService feeTypeService) {
        this.feeTypeService = feeTypeService;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    List<FeeTypeResponse> list() {
        return feeTypeService.listActive().stream().map(FeeTypeResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_FEE_EDIT)
    ResponseEntity<FeeTypeResponse> create(@Valid @RequestBody CreateFeeTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(FeeTypeResponse.from(feeTypeService.create(request.name())));
    }
}
