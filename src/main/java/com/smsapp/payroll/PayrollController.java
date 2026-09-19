package com.smsapp.payroll;

import com.smsapp.payroll.PayrollDtos.GeneratePayrollRequest;
import com.smsapp.payroll.PayrollDtos.PayrollRecordResponse;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Payroll (plan section 2, HR & payroll). Generating a record is SCHOOL_ADMIN
 * only; a staff member's own payroll history is served under
 * {@code /api/v1/me/payroll}, ownership-scoped by their own user id.
 */
@RestController
@RequestMapping("/api/v1")
public class PayrollController {

    private final PayrollService payrollService;

    public PayrollController(PayrollService payrollService) {
        this.payrollService = payrollService;
    }

    /**
     * Generate a payroll record for a staff member's month/year. SCHOOL_ADMIN only.
     * {@code netPay = baseSalary - deductions}, {@code baseSalary} taken from the
     * staff member's current profile. 404 if they have no staff profile, 409 if a
     * record already exists for that staff member + month + year.
     */
    @PostMapping("/payroll")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<PayrollRecordResponse> generate(@Valid @RequestBody GeneratePayrollRequest request) {
        PayrollRecord created = payrollService.generate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(PayrollRecordResponse.from(created));
    }

    /** The caller's own payroll history (SCHOOL_ADMIN or TEACHER, ownership-scoped by their own user id). */
    @GetMapping("/me/payroll")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<PayrollRecordResponse> ownPayroll(Authentication authentication) {
        return payrollService.ownPayroll(userId(authentication)).stream()
                .map(PayrollRecordResponse::from).toList();
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
