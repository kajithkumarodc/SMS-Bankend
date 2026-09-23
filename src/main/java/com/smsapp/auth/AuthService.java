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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid credentials";
    private static final String INVALID_REFRESH_TOKEN = "Invalid refresh token";
    private static final String DETAIL_EMAIL = "email";
    private static final long ACCESS_TOKEN_TTL_SECONDS = 3600;
    private static final long REFRESH_TOKEN_TTL_DAYS = 30;
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserRepository userRepository, RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
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
        String accessToken = issueAccessToken(user, roles);
        String refreshToken = issueRefreshToken(user.getId());

        auditService.logAs(user.getId(), AuditActions.LOGIN_SUCCESS, AuditActions.USER, user.getId(),
                Map.of(DETAIL_EMAIL, request.email()));

        return new LoginResponse(accessToken, refreshToken,
                new AuthenticatedUser(user.getId().toString(), user.getFullName(), roles));
    }

    /**
     * Exchanges a still-active refresh token for a new access token + a new,
     * rotated refresh token. The presented token is revoked as part of the same
     * exchange, so it can never be used a second time.
     * <p>
     * If the presented token is found but already revoked, that is a replay of a
     * token that has already been rotated (or explicitly logged out) -- a signal
     * the raw value has leaked -- so every active token for that user is revoked
     * rather than just rejecting this one call.
     */
    // noRollbackFor: the reuse-detection branch below deliberately revokes every
    // active token for the user and THEN throws BadCredentialsException to report
    // the failure to the caller. Spring's default rollback-on-RuntimeException
    // would otherwise undo that revocation along with the exception -- the one
    // write this method must keep no matter which branch it exits through.
    @Transactional(noRollbackFor = BadCredentialsException.class)
    public RefreshResponse refresh(String rawRefreshToken) {
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(() -> new BadCredentialsException(INVALID_REFRESH_TOKEN));

        OffsetDateTime now = OffsetDateTime.now();

        if (existing.getRevokedAt() != null) {
            refreshTokenRepository.revokeAllActiveForUser(existing.getUserId(), now);
            auditService.log(AuditActions.TOKEN_REUSE_DETECTED, AuditActions.REFRESH_TOKEN, existing.getId(),
                    Map.of("userId", existing.getUserId()));
            throw new BadCredentialsException(INVALID_REFRESH_TOKEN);
        }

        if (existing.getExpiresAt().isBefore(now)) {
            throw new BadCredentialsException(INVALID_REFRESH_TOKEN);
        }

        User user = userRepository.findById(existing.getUserId())
                .orElseThrow(() -> new BadCredentialsException(INVALID_REFRESH_TOKEN));
        List<String> roles = roleRepository.findNamesByUserId(user.getId());

        String newRawRefreshToken = generateOpaqueToken();
        RefreshToken rotated = persistRefreshToken(user.getId(), newRawRefreshToken);

        existing.setRevokedAt(now);
        existing.setReplacedByTokenId(rotated.getId());
        refreshTokenRepository.save(existing);

        String accessToken = issueAccessToken(user, roles);

        auditService.logAs(user.getId(), AuditActions.TOKEN_REFRESHED, AuditActions.REFRESH_TOKEN, rotated.getId(),
                Map.of());

        return new RefreshResponse(accessToken, newRawRefreshToken,
                new AuthenticatedUser(user.getId().toString(), user.getFullName(), roles));
    }

    /** Revokes one refresh token (best-effort -- an already-revoked/unknown token is not an error). */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(rawRefreshToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(OffsetDateTime.now());
                refreshTokenRepository.save(token);
                auditService.logAs(token.getUserId(), AuditActions.LOGOUT, AuditActions.REFRESH_TOKEN,
                        token.getId(), Map.of());
            }
        });
    }

    /** Revokes every active refresh token for a user -- "sign out of all devices." */
    @Transactional
    public void logoutAll(UUID userId) {
        refreshTokenRepository.revokeAllActiveForUser(userId, OffsetDateTime.now());
        auditService.logAs(userId, AuditActions.LOGOUT_ALL, AuditActions.USER, userId, Map.of());
    }

    private String issueAccessToken(User user, List<String> roles) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .claim("roles", roles)
                .claim("name", user.getFullName())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS))
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private String issueRefreshToken(UUID userId) {
        String raw = generateOpaqueToken();
        persistRefreshToken(userId, raw);
        return raw;
    }

    private RefreshToken persistRefreshToken(UUID userId, String rawToken) {
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(hash(rawToken));
        token.setExpiresAt(OffsetDateTime.now().plusDays(REFRESH_TOKEN_TTL_DAYS));
        return refreshTokenRepository.save(token);
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hash of the raw token, hex-encoded -- the raw value is never persisted (see RefreshToken javadoc). */
    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void auditLoginFailed(UUID userId, String email, String reason) {
        auditService.logAs(userId, AuditActions.LOGIN_FAILED, AuditActions.USER, userId,
                Map.of(DETAIL_EMAIL, email, "reason", reason));
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String token, String refreshToken, AuthenticatedUser user) {
    }

    public record RefreshResponse(String token, String refreshToken, AuthenticatedUser user) {
    }

    public record AuthenticatedUser(String id, String name, List<String> roles, List<String> permissions,
                                    boolean mustChangePassword) {
    }
}
