package com.smsapp.payroll;

import com.smsapp.payroll.PayrollManagementDtos.GenerateRequest;
import com.smsapp.payroll.PayrollManagementDtos.PayRequest;
import com.smsapp.payroll.PayrollManagementDtos.PayrollDetail;
import com.smsapp.payroll.PayrollManagementDtos.PayrollRow;
import com.smsapp.payroll.PayrollManagementDtos.RoleOption;
import com.smsapp.payroll.PayrollManagementDtos.UpdateRequest;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Human Resource > Payroll, gated by the PAYROLL_* permissions (seeded in V22): PAYROLL_VIEW to read, PAYROLL_CREATE
 * to generate, edit or revert, PAYROLL_APPROVE to pay (and to revert a paid payroll).
 */
@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollManagementController {

    private final PayrollManagementService service;

    public PayrollManagementController(PayrollManagementService service) {
        this.service = service;
    }

    /** The Role choices for the criteria. */
    @GetMapping("/roles")
    @PreAuthorize(Permissions.HAS_PAYROLL_VIEW)
    List<RoleOption> roles() {
        return service.roles();
    }

    /** The active staff of one role with the state of their payroll for a month. */
    @GetMapping
    @PreAuthorize(Permissions.HAS_PAYROLL_VIEW)
    List<PayrollRow> list(@RequestParam UUID roleId, @RequestParam int month,
                          @RequestParam int year) {
        return service.list(roleId, month, year);
    }

    @PostMapping("/generate")
    @PreAuthorize(Permissions.HAS_PAYROLL_CREATE)
    ResponseEntity<PayrollDetail> generate(@Valid @RequestBody GenerateRequest request) {
        PayrollRecord created = service.generate(request.staffProfileId(), request.month(), request.year());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.detail(created.getId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_PAYROLL_VIEW)
    PayrollDetail get(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_PAYROLL_CREATE)
    PayrollDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize(Permissions.HAS_PAYROLL_APPROVE)
    PayrollDetail pay(@PathVariable UUID id, @Valid @RequestBody PayRequest request) {
        return service.pay(id, request);
    }

    @PostMapping("/{id}/revert")
    @PreAuthorize(Permissions.HAS_PAYROLL_CREATE)
    ResponseEntity<Void> revert(@PathVariable UUID id, Authentication authentication) {
        boolean canRevertPaid = authentication.getAuthorities().stream()
                .anyMatch(a -> Permissions.PAYROLL_APPROVE.equals(a.getAuthority()));
        service.revert(id, canRevertPaid);
        return ResponseEntity.noContent().build();
    }
}
