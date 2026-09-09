package com.smsapp.fee;

import com.smsapp.fee.FeeDtos.CheckoutResponse;
import com.smsapp.fee.FeeDtos.CreateFeeStructureRequest;
import com.smsapp.fee.FeeDtos.CreateInvoiceRequest;
import com.smsapp.fee.FeeDtos.FeeStructureResponse;
import com.smsapp.fee.FeeDtos.InvoiceResponse;
import com.smsapp.user.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Fee-management API (plan section 2). Fee structures and invoices are created by
 * a SCHOOL_ADMIN only; a TEACHER may read invoices; a PARENT reads their own
 * child's invoices through {@code /api/v1/me/children/{studentId}/invoices}.
 */
@RestController
@RequestMapping("/api/v1")
public class FeeController {

    private final FeeService feeService;

    public FeeController(FeeService feeService) {
        this.feeService = feeService;
    }

    /** Create a fee structure. SCHOOL_ADMIN only; a TEACHER gets 403. 404 if the school is not in the tenant. */
    @PostMapping("/fee-structures")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<FeeStructureResponse> createFeeStructure(
            @Valid @RequestBody CreateFeeStructureRequest request, Authentication authentication) {
        FeeStructure created = feeService.createFeeStructure(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(FeeStructureResponse.from(created));
    }

    /** All fee structures for the caller's tenant (most recent first). */
    @GetMapping("/fee-structures")
    List<FeeStructureResponse> listFeeStructures(Authentication authentication) {
        return feeService.listFeeStructures(tenantId(authentication)).stream()
                .map(FeeStructureResponse::from).toList();
    }

    /**
     * Generate a PENDING invoice for a student against a fee structure. SCHOOL_ADMIN
     * only. 404 if the student or the fee structure is not in the caller's tenant.
     */
    @PostMapping("/invoices")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public ResponseEntity<InvoiceResponse> createInvoice(@Valid @RequestBody CreateInvoiceRequest request,
                                                         Authentication authentication) {
        Invoice created = feeService.createInvoice(tenantId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(InvoiceResponse.from(created));
    }

    /**
     * Invoices for one student. SCHOOL_ADMIN or TEACHER only -- a parent uses the
     * ownership-scoped {@code /api/v1/me/children/{studentId}/invoices}. 404 if the
     * student is not in the caller's tenant.
     */
    @GetMapping("/invoices")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_TEACHER)
    List<InvoiceResponse> listInvoices(@RequestParam UUID studentId, Authentication authentication) {
        return feeService.listInvoicesForStudent(tenantId(authentication), studentId).stream()
                .map(InvoiceResponse::from).toList();
    }

    /**
     * Create a Razorpay Order for an invoice and return only what the browser
     * Checkout widget needs (order id + public key id + amount). A SCHOOL_ADMIN may
     * check out any invoice in the tenant; a PARENT may check out only their own
     * child's invoice (enforced in the service -- 404 otherwise). 409 if it is
     * already paid.
     */
    @PostMapping("/invoices/{invoiceId}/checkout")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN_OR_PARENT)
    public CheckoutResponse checkout(@PathVariable UUID invoiceId, Authentication authentication) {
        return feeService.startCheckout(tenantId(authentication), invoiceId, parentScope(authentication));
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }

    /**
     * The caller's user id when they are acting as a PARENT (so the service can
     * restrict the invoice to their own children), or {@code null} when they hold
     * SCHOOL_ADMIN and may act on any invoice in the tenant.
     */
    static UUID parentScope(Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> Roles.ROLE_SCHOOL_ADMIN.equals(a.getAuthority()));
        if (isAdmin) {
            return null;
        }
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
