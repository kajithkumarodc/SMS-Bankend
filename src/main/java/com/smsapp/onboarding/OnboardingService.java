package com.smsapp.onboarding;

import com.smsapp.common.ApiException;
import com.smsapp.onboarding.OnboardingDtos.RegisterSchoolRequest;
import com.smsapp.onboarding.OnboardingDtos.RegisterSchoolResponse;
import com.smsapp.school.School;
import com.smsapp.school.SchoolRepository;
import com.smsapp.tenant.Tenant;
import com.smsapp.tenant.TenantContext;
import com.smsapp.tenant.TenantRepository;
import com.smsapp.user.Role;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.Roles;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import com.smsapp.user.UserRole;
import com.smsapp.user.UserRoleRepository;
import com.smsapp.user.UserStatus;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Self-service tenant onboarding (plan section 1 "tenant control plane" / section
 * 3 multi-tenancy): a new school registers itself, with no existing admin creating
 * them by hand. This is the one deliberate exception to "every request carries a
 * JWT" -- there is, by definition, no tenant and no user yet.
 *
 * <p>Everything after the tenant row is created is written under a freshly
 * established tenant-RLS session context for the brand-new tenant id, the same
 * technique {@code AuthService#login} uses to read a not-yet-authenticated user's
 * row: {@link TenantContext#setCurrentTenant} plus an explicit
 * {@code set_config('app.current_tenant_id', ...)} on this transaction, since the
 * usual source of that context -- a resolved JWT on the request -- does not exist
 * here. The whole method is one {@code @Transactional} unit: if any step fails,
 * the tenant row itself rolls back too, so a failed registration never leaves an
 * orphaned tenant with no admin able to log into it.
 */
@Service
public class OnboardingService {

    private final TenantRepository tenantRepository;
    private final SchoolRepository schoolRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    public OnboardingService(TenantRepository tenantRepository, SchoolRepository schoolRepository,
                             RoleRepository roleRepository, UserRepository userRepository,
                             UserRoleRepository userRoleRepository, PasswordEncoder passwordEncoder,
                             EntityManager entityManager) {
        this.tenantRepository = tenantRepository;
        this.schoolRepository = schoolRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.entityManager = entityManager;
    }

    /**
     * @throws ApiException 409 if {@code schoolIdentifier} is already taken, or (defensively --
     *         see the class javadoc; a brand-new tenant can never actually already have this
     *         email) if {@code adminEmail} is somehow already used within it.
     */
    @Transactional
    public RegisterSchoolResponse registerSchool(RegisterSchoolRequest request) {
        String identifier = normalize(request.schoolIdentifier());
        String adminEmail = normalize(request.adminEmail());
        String schoolName = request.schoolName().trim();

        if (tenantRepository.findByIdentifier(identifier).isPresent()) {
            throw identifierConflict(identifier);
        }

        // `tenants` is the control-plane table (plan section 1): no RLS, so this insert
        // needs no tenant context (see migration V3).
        Tenant tenant = new Tenant();
        tenant.setName(schoolName);
        tenant.setIdentifier(identifier);
        tenant.setCreatedAt(OffsetDateTime.now());
        tenant.setStatus("ACTIVE");

        Tenant savedTenant;
        try {
            savedTenant = tenantRepository.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException ex) {
            // Lost the race against a concurrent registration of the same identifier.
            throw identifierConflict(identifier);
        }
        UUID tenantId = savedTenant.getId();

        // Every table from here down IS under tenant RLS. There is no JWT for
        // TenantRequestFilter to have resolved a tenant from, so establish the session's
        // tenant context by hand for this brand-new tenant -- same technique as
        // AuthService#login, the other place a tenant context must exist before any
        // authenticated session does.
        TenantContext.setCurrentTenant(tenantId.toString());
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant_id', :tenantId, true)")
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        School school = new School();
        school.setTenantId(tenantId);
        school.setName(schoolName);
        school.setCreatedAt(OffsetDateTime.now());
        schoolRepository.save(school);

        // Roles are per-tenant (see migration V1: UNIQUE(tenant_id, name)), so a fresh
        // tenant starts with none -- seed exactly the one this flow needs.
        Role adminRole = new Role();
        adminRole.setTenantId(tenantId);
        adminRole.setName(Roles.SCHOOL_ADMIN);
        Role savedRole = roleRepository.save(adminRole);

        // Defensive per the ticket: for a tenant that was just created this instant, no user
        // -- let alone this email -- can already exist under it. Checked anyway, cheaply.
        if (userRepository.findByTenantIdAndEmail(tenantId, adminEmail).isPresent()) {
            throw adminEmailConflict();
        }

        User admin = new User();
        admin.setTenantId(tenantId);
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(request.adminPassword()));
        admin.setFullName(request.adminFullName().trim());
        admin.setStatus(UserStatus.ACTIVE);

        User savedAdmin;
        try {
            savedAdmin = userRepository.saveAndFlush(admin);
        } catch (DataIntegrityViolationException ex) {
            throw adminEmailConflict();
        }

        UserRole userRole = new UserRole();
        userRole.setUserId(savedAdmin.getId());
        userRole.setRoleId(savedRole.getId());
        userRole.setTenantId(tenantId);
        userRoleRepository.save(userRole);

        // Deliberately NOT audited here: AuditService#logAs runs in its own REQUIRES_NEW
        // transaction, which -- unlike every other logAs call in the app -- would try to
        // insert an audit_log row referencing this tenant_id before this surrounding
        // transaction (the one that creates the tenant) has committed. Every other logAs
        // call references an already-committed tenant from an earlier request; this is the
        // one place the referenced tenant is brand new, so the FK would fail. The caller
        // (OnboardingController) logs TENANT_REGISTERED after this method returns, once the
        // tenant is genuinely committed.
        return new RegisterSchoolResponse(tenantId, schoolName, identifier, adminEmail, savedAdmin.getId());
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException identifierConflict(String identifier) {
        return new ApiException(
                "School identifier '" + identifier + "' is already taken -- please choose another",
                HttpStatus.CONFLICT);
    }

    private static ApiException adminEmailConflict() {
        return new ApiException("That admin email is already in use", HttpStatus.CONFLICT);
    }
}
