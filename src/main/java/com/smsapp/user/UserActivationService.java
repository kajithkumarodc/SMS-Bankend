package com.smsapp.user;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and consumes the one-time activation link a newly-created portal user gets emailed (plan
 * Phase 4.5 part 14) -- lets them set their own password instead of a plaintext temporary password
 * ever appearing in an email. Separate from {@link UserService}'s admin-facing "reveal a temporary
 * password once in the UI" flow, which stays exactly as it was for admin-created staff accounts.
 */
@Service
public class UserActivationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final long EXPIRY_HOURS = 72;

    private final UserActivationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserActivationService(UserActivationTokenRepository tokenRepository, UserRepository userRepository,
                                  PasswordEncoder passwordEncoder, AuditService auditService) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /** Issues a fresh, unguessable token for {@code userId}, valid for 72 hours. Returns the raw token to embed in a link. */
    @Transactional
    public String issueToken(UUID userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        UserActivationToken entity = new UserActivationToken();
        entity.setUserId(userId);
        entity.setToken(token);
        entity.setExpiresAt(OffsetDateTime.now().plusHours(EXPIRY_HOURS));
        tokenRepository.save(entity);

        auditService.log(AuditActions.USER_ACTIVATION_ISSUED, AuditActions.USER_ACTIVATION_TOKEN, entity.getId(),
                Map.of("userId", userId.toString()));
        return token;
    }

    /**
     * Consumes a token and sets the user's own chosen password -- the only way an invited parent
     * account gets a usable password (plan part 14).
     *
     * @throws ApiException 400 if the token is unknown, already used, or expired.
     */
    @Transactional
    public void activate(String token, String newPassword) {
        UserActivationToken entity = tokenRepository.findByToken(token)
                .orElseThrow(() -> new ApiException("Invalid or expired activation link", HttpStatus.BAD_REQUEST));
        if (entity.getUsedAt() != null || entity.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ApiException("Invalid or expired activation link", HttpStatus.BAD_REQUEST);
        }
        User user = userRepository.findById(entity.getUserId())
                .orElseThrow(() -> new ApiException("Invalid or expired activation link", HttpStatus.BAD_REQUEST));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        entity.setUsedAt(OffsetDateTime.now());
        tokenRepository.save(entity);

        auditService.log(AuditActions.USER_ACTIVATED, AuditActions.USER, user.getId(), Map.of());
    }
}
