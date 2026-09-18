package com.smsapp.onboarding;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.onboarding.OnboardingDtos.RegisterSchoolRequest;
import com.smsapp.onboarding.OnboardingDtos.RegisterSchoolResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public, unauthenticated tenant self-registration (plan sections 1 and 3). This
 * is the one deliberate exception to "every endpoint requires a JWT" --
 * {@code /register-school} is explicitly {@code permitAll} in
 * {@code SecurityConfig}. Bean Validation on {@link RegisterSchoolRequest} is the
 * only defense this layer adds against abusive/malformed input; production
 * deployment should additionally add IP-based rate limiting at the
 * infrastructure/gateway level (nginx/API gateway, load balancer, or a WAF) --
 * that is a Section 7 (deployment/performance) concern, deliberately not built
 * into the application layer here.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final AuditService auditService;

    public OnboardingController(OnboardingService onboardingService, AuditService auditService) {
        this.onboardingService = onboardingService;
        this.auditService = auditService;
    }

    /**
     * Registers a new school: a fresh tenant, its default school record, a
     * per-tenant SCHOOL_ADMIN role, and the admin's own login. Does NOT auto-login
     * -- the response directs the caller to sign in at {@code /login} with the new
     * {@code schoolIdentifier}, consistent with the existing login flow. 409 if the
     * identifier (or, defensively, the admin email) is already taken.
     */
    @PostMapping("/register-school")
    public ResponseEntity<RegisterSchoolResponse> registerSchool(@Valid @RequestBody RegisterSchoolRequest request) {
        RegisterSchoolResponse response = onboardingService.registerSchool(request);

        // Logged here, not inside OnboardingService: AuditService#logAs writes in its own
        // REQUIRES_NEW transaction, which would otherwise try to insert an audit_log row
        // referencing this tenant_id before OnboardingService's transaction (the one that
        // creates the tenant) has committed -- see the javadoc on OnboardingService#registerSchool.
        // By construction this call only happens after that transaction returned successfully,
        // i.e. after commit.
        auditService.logAs(response.tenantId(), response.adminUserId(), AuditActions.TENANT_REGISTERED,
                AuditActions.TENANT, response.tenantId(),
                Map.of("schoolIdentifier", response.schoolIdentifier(), "adminEmail", response.adminEmail()));

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
