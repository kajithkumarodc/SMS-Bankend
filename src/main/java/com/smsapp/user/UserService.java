package com.smsapp.user;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.user.UserDtos.CreateUserRequest;
import com.smsapp.user.UserDtos.CreateUserResponse;
import com.smsapp.user.UserDtos.ResetPasswordResponse;
import com.smsapp.user.UserDtos.UpdateUserRequest;
import com.smsapp.user.UserDtos.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local user provisioning (RBAC Phase 1 / decisions doc #9: authentication must
 * work locally without any third-party service). Until now no endpoint created a
 * {@code User} row at all -- accounts were seeded directly into the database.
 * Password resets never email/SMS a link: an admin-triggered reset returns a
 * one-time temporary password in the response body and flags the account so
 * the next login must change it, which works with zero external providers.
 */
@Service
public class UserService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int GENERATED_PASSWORD_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       UserRoleRepository userRoleRepository, PasswordEncoder passwordEncoder,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * @throws ApiException 409 if the email is already in use, 400 if any role id
     *         does not exist.
     */
    @Transactional
    public CreateUserResponse create(CreateUserRequest request) {
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new ApiException("A user with email '" + email + "' already exists", HttpStatus.CONFLICT);
        }
        requireValidRoleIds(request.roleIds());

        boolean generated = request.initialPassword() == null || request.initialPassword().isBlank();
        String plaintextPassword = generated ? generatePassword() : request.initialPassword();

        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName().trim());
        user.setPasswordHash(passwordEncoder.encode(plaintextPassword));
        user.setStatus(UserStatus.ACTIVE);
        // Always true on creation: the caller (an admin) chose or generated this
        // password on the user's behalf, so the user must set their own on first login.
        user.setMustChangePassword(true);

        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A user with email '" + email + "' already exists", HttpStatus.CONFLICT);
        }

        assignRoles(saved.getId(), request.roleIds());

        auditService.log(AuditActions.USER_CREATED, AuditActions.USER, saved.getId(),
                Map.of("email", saved.getEmail(), "roleCount", request.roleIds().size()));

        UserResponse response = UserResponse.from(saved, roleRepository.findNamesByUserId(saved.getId()));
        return new CreateUserResponse(response, generated ? plaintextPassword : null);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listAll() {
        return userRepository.findAllByOrderByFullName().stream()
                .map(u -> UserResponse.from(u, roleRepository.findNamesByUserId(u.getId())))
                .toList();
    }

    /** @throws ApiException 404 if no such user. */
    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        User user = requireUser(id);
        return UserResponse.from(user, roleRepository.findNamesByUserId(user.getId()));
    }

    /**
     * @throws ApiException 404 if no such user, 400 if {@code status} is invalid or
     *         any role id does not exist.
     */
    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = requireUser(id);
        String status = UserStatus.normalizeOrNull(request.status());
        if (status == null) {
            throw new ApiException("Status must be ACTIVE or INACTIVE", HttpStatus.BAD_REQUEST);
        }
        requireValidRoleIds(request.roleIds());

        user.setFullName(request.fullName().trim());
        user.setStatus(status);
        User saved = userRepository.save(user);

        userRoleRepository.deleteByUserId(id);
        assignRoles(id, request.roleIds());

        auditService.log(AuditActions.USER_UPDATED, AuditActions.USER, id,
                Map.of("status", status, "roleCount", request.roleIds().size()));
        return UserResponse.from(saved, roleRepository.findNamesByUserId(id));
    }

    /**
     * Admin-triggered reset: generates a new temporary password, returned once, and
     * flags the account so the next login must change it.
     *
     * @throws ApiException 404 if no such user.
     */
    @Transactional
    public ResetPasswordResponse resetPassword(UUID id) {
        User user = requireUser(id);
        String temporaryPassword = generatePassword();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);

        auditService.log(AuditActions.USER_PASSWORD_RESET, AuditActions.USER, id, Map.of());
        return new ResetPasswordResponse(temporaryPassword);
    }

    /**
     * Self-service password change -- the only path that clears {@code mustChangePassword}.
     *
     * @throws ApiException 400 if {@code currentPassword} does not match.
     */
    @Transactional
    public void changeOwnPassword(UUID userId, String currentPassword, String newPassword) {
        User user = requireUser(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        auditService.log(AuditActions.USER_PASSWORD_CHANGED, AuditActions.USER, userId, Map.of());
    }

    /**
     * Creates a portal account for a parent/guardian discovered during online-admission approval
     * (plan Phase 4.5 part 13) -- the same creation path as {@link #create}, exposed with plain
     * parameters (rather than the package-private request/response DTOs) so another module can reuse
     * it instead of building a second account-creation implementation. The generated password is
     * never returned to the caller: the admission flow issues an activation link instead (see
     * {@code UserActivationService}), so this account is only usable once that link is followed.
     *
     * @throws ApiException 409 if the email is already in use. Callers should check for an existing
     *         user first (see {@link UserRepository#findByEmail}) and reuse it instead of calling
     *         this -- this exception is a last-resort safety net against a lost race, not the primary
     *         dedupe path.
     */
    @Transactional
    public User createGuardianAccount(String email, String fullName, UUID roleId) {
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ApiException("A user with email '" + normalizedEmail + "' already exists", HttpStatus.CONFLICT);
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setFullName(fullName.trim());
        user.setPasswordHash(passwordEncoder.encode(generatePassword()));
        user.setStatus(UserStatus.ACTIVE);
        user.setMustChangePassword(true);

        User saved;
        try {
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException("A user with email '" + normalizedEmail + "' already exists", HttpStatus.CONFLICT);
        }

        UserRole link = new UserRole();
        link.setUserId(saved.getId());
        link.setRoleId(roleId);
        userRoleRepository.save(link);

        auditService.log(AuditActions.USER_CREATED, AuditActions.USER, saved.getId(),
                Map.of("email", saved.getEmail(), "source", "admission_application"));
        return saved;
    }

    private void assignRoles(UUID userId, Set<UUID> roleIds) {
        List<UserRole> links = roleIds.stream().map(roleId -> {
            UserRole link = new UserRole();
            link.setUserId(userId);
            link.setRoleId(roleId);
            return link;
        }).toList();
        userRoleRepository.saveAll(links);
    }

    private void requireValidRoleIds(Set<UUID> roleIds) {
        long found = roleRepository.findAllById(roleIds).size();
        if (found != roleIds.size()) {
            throw new ApiException("One or more role ids do not exist", HttpStatus.BAD_REQUEST);
        }
    }

    private User requireUser(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException("User not found", HttpStatus.NOT_FOUND));
    }

    private static String generatePassword() {
        StringBuilder sb = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
