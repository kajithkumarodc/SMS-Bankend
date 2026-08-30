package com.smsapp.auth;

import com.smsapp.tenant.Tenant;
import com.smsapp.tenant.TenantContext;
import com.smsapp.tenant.TenantRepository;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final EntityManager entityManager;

    public AuthService(TenantRepository tenantRepository, UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, EntityManager entityManager) {
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.entityManager = entityManager;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        // Resolve the tenant from the school identifier (subdomain/slug) before any
        // tenant context exists. `tenants` is a control-plane table and is not under
        // tenant RLS (see migration V3).
        Tenant tenant = tenantRepository.findByIdentifier(request.schoolIdentifier())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        UUID tenantId = tenant.getId();

        TenantContext.setCurrentTenant(tenantId.toString());
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant_id', :tenantId, true)")
            .setParameter("tenantId", tenantId.toString())
            .getSingleResult();

        User user = userRepository.findByTenantIdAndEmail(tenantId, request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        List<String> roles = roleRepository.findNamesByUserId(user.getId());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("roles", roles)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        return new LoginResponse(token,
                new AuthenticatedUser(user.getId().toString(), user.getFullName(), tenantId.toString(), roles));
    }

    public record LoginRequest(String schoolIdentifier, String email, String password) {
    }

    public record LoginResponse(String token, AuthenticatedUser user) {
    }

    public record AuthenticatedUser(String id, String name, String tenantId, List<String> roles) {
    }
}
