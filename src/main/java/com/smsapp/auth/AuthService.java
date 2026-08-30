package com.smsapp.auth;

import com.smsapp.tenant.TenantContext;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final EntityManager entityManager;

    public AuthService(UserRepository userRepository, RoleRepository roleRepository, PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder, EntityManager entityManager) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.entityManager = entityManager;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        UUID tenantId = parseTenantId(request.tenantId());
        TenantContext.setCurrentTenant(tenantId.toString());
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant_id', :tenantId, true)")
            .setParameter("tenantId", tenantId.toString())
            .getSingleResult();
        User user = userRepository.findByTenantIdAndEmail(tenantId, request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("roles", roleRepository.findNamesByUserId(user.getId()))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        return new LoginResponse(jwtEncoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
    }

    private UUID parseTenantId(String tenantId) {
        try {
            return UUID.fromString(tenantId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tenant identifier");
        }
    }

    public record LoginRequest(String tenantId, String email, String password) {
    }

    public record LoginResponse(String token) {
    }
}
