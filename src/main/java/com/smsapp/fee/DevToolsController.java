package com.smsapp.fee;

import com.smsapp.fee.FeeDtos.InvoiceResponse;
import com.smsapp.user.Roles;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ⚠️ LOCAL-DEVELOPMENT CONVENIENCE ONLY — NOT FOR ANY DEPLOYED ENVIRONMENT. ⚠️
 *
 * <p>These endpoints let a local demo reach the "paid invoice" state without a
 * public webhook tunnel (Razorpay cannot call {@code localhost}). Real payment
 * confirmation MUST always come through {@link RazorpayWebhookController}, which
 * verifies the HMAC signature on a genuine Razorpay {@code order.paid} event —
 * never through this controller.
 *
 * <p>The whole controller is bean-registered <em>only</em> when
 * {@code app.dev-tools-enabled=true} (see {@code application.yml}, which defaults
 * it to {@code false}). When the flag is false or absent, this class is not a
 * Spring bean at all: the routes below simply do not exist and a request to them
 * gets a plain {@code 404}. There is deliberately no runtime toggle and no way to
 * enable it per-request.
 *
 * <p><b>Before any real deployment:</b> keep {@code app.dev-tools-enabled=false}
 * (its default), or delete this controller and {@link FeeService#simulatePaymentSuccess}
 * outright. Do not "secure" it and ship it — a payment side-channel that bypasses
 * gateway verification has no place in production even behind auth.
 */
@RestController
@RequestMapping("/api/v1/dev")
@ConditionalOnProperty(name = "app.dev-tools-enabled", havingValue = "true")
public class DevToolsController {

    private final FeeService feeService;

    public DevToolsController(FeeService feeService) {
        this.feeService = feeService;
    }

    /**
     * DEV-ONLY. Flip an invoice to {@code PAID} as if a verified Razorpay
     * {@code order.paid} webhook had arrived. SCHOOL_ADMIN only, tenant-scoped,
     * idempotent. 404 if the invoice is not in the caller's tenant.
     */
    @PostMapping("/invoices/{invoiceId}/simulate-payment-success")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    public InvoiceResponse simulatePaymentSuccess(@PathVariable UUID invoiceId, Authentication authentication) {
        return InvoiceResponse.from(feeService.simulatePaymentSuccess(tenantId(authentication), invoiceId));
    }

    private static UUID tenantId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        return UUID.fromString(jwt.getClaimAsString("tenant_id"));
    }
}
