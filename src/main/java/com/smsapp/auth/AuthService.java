package com.smsapp.auth;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
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
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid credentials";
    private static final String DETAIL_EMAIL = "email";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null) {
            auditLoginFailed(null, request.email(), "user_not_found");
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditLoginFailed(user.getId(), request.email(), "bad_password");
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }

        List<String> roles = roleRepository.findNamesByUserId(user.getId());
        // RBAC Phase 1: database-driven permission grants, baked into the JWT at
        // login same as roles -- no per-request DB lookup needed for authorization.
        List<String> permissions = roleRepository.findPermissionNamesByUserId(user.getId());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("roles", roles)
                .claim("permissions", permissions)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        auditService.logAs(user.getId(), AuditActions.LOGIN_SUCCESS, AuditActions.USER, user.getId(),
                Map.of(DETAIL_EMAIL, request.email()));

        return new LoginResponse(token, new AuthenticatedUser(user.getId().toString(), user.getFullName(), roles,
                permissions, user.isMustChangePassword()));
    }

    private void auditLoginFailed(UUID userId, String email, String reason) {
        auditService.logAs(userId, AuditActions.LOGIN_FAILED, AuditActions.USER, userId,
                Map.of(DETAIL_EMAIL, email, "reason", reason));
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String token, AuthenticatedUser user) {
    }

    public record AuthenticatedUser(String id, String name, List<String> roles, List<String> permissions,
                                    boolean mustChangePassword) {
    }
}
