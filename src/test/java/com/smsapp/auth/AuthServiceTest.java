package com.smsapp.auth;

import com.smsapp.audit.AuditService;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private AuditService auditService;

    private AuthService newService() {
        return new AuthService(userRepository, roleRepository, refreshTokenRepository, passwordEncoder,
                jwtEncoder, auditService);
    }

    private void stubJwtEncoder() {
        var jwt = org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.Jwt.class);
        lenient().when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);
        lenient().when(jwt.getTokenValue()).thenReturn("token");
    }

    @Test
    void loginReturnsJwtAndUserForValidCredentials() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("admin@example.com");
        user.setPasswordHash("encoded");
        user.setFullName("Admin User");

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
        when(roleRepository.findNamesByUserId(user.getId())).thenReturn(List.of("SCHOOL_ADMIN"));
        stubJwtEncoder();

        var response = newService().login(new AuthService.LoginRequest(user.getEmail(), "secret"));

        assertThat(response.token()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.user().id()).isEqualTo(user.getId().toString());
        assertThat(response.user().name()).isEqualTo("Admin User");
        assertThat(response.user().roles()).containsExactly("SCHOOL_ADMIN");
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void loginRejectsInvalidPassword() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("admin@example.com");
        user.setPasswordHash("encoded");

        when(userRepository.findByEmail(eq(user.getEmail()))).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> newService().login(new AuthService.LoginRequest(user.getEmail(), "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(userRepository.findByEmail("no-such-user@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService()
                .login(new AuthService.LoginRequest("no-such-user@example.com", "secret")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refreshRotatesAnActiveToken() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName("Admin User");

        RefreshToken stored = new RefreshToken();
        stored.setId(UUID.randomUUID());
        stored.setUserId(user.getId());
        stored.setExpiresAt(OffsetDateTime.now().plusDays(1));

        when(refreshTokenRepository.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(roleRepository.findNamesByUserId(user.getId())).thenReturn(List.of("TEACHER"));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken arg = invocation.getArgument(0);
            if (arg.getId() == null) {
                arg.setId(UUID.randomUUID());
            }
            return arg;
        });
        stubJwtEncoder();

        var response = newService().refresh("some-raw-token");

        assertThat(response.token()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getReplacedByTokenId()).isNotNull();
        verify(refreshTokenRepository, never()).revokeAllActiveForUser(any(), any());
    }

    @Test
    void refreshRejectsAnExpiredToken() {
        RefreshToken stored = new RefreshToken();
        stored.setId(UUID.randomUUID());
        stored.setUserId(UUID.randomUUID());
        stored.setExpiresAt(OffsetDateTime.now().minusMinutes(1));

        when(refreshTokenRepository.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> newService().refresh("expired-token"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refreshOfAnAlreadyRevokedTokenRevokesTheWholeChain() {
        UUID userId = UUID.randomUUID();
        RefreshToken stored = new RefreshToken();
        stored.setId(UUID.randomUUID());
        stored.setUserId(userId);
        stored.setExpiresAt(OffsetDateTime.now().plusDays(1));
        stored.setRevokedAt(OffsetDateTime.now().minusMinutes(1));

        when(refreshTokenRepository.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> newService().refresh("reused-token"))
                .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokenRepository).revokeAllActiveForUser(eq(userId), any());
    }

    @Test
    void refreshRejectsAnUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().refresh("unknown-token"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void logoutRevokesOnlyTheMatchingActiveToken() {
        RefreshToken stored = new RefreshToken();
        stored.setId(UUID.randomUUID());
        stored.setUserId(UUID.randomUUID());
        stored.setExpiresAt(OffsetDateTime.now().plusDays(1));

        when(refreshTokenRepository.findByTokenHash(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(stored));

        newService().logout("some-raw-token");

        assertThat(stored.getRevokedAt()).isNotNull();
        verify(refreshTokenRepository).save(stored);
    }

    @Test
    void logoutWithNoTokenIsANoOp() {
        newService().logout(null);
        newService().logout("");

        verify(refreshTokenRepository, never()).findByTokenHash(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void logoutAllRevokesEveryActiveTokenForTheUser() {
        UUID userId = UUID.randomUUID();

        newService().logoutAll(userId);

        verify(refreshTokenRepository, times(1)).revokeAllActiveForUser(eq(userId), any());
    }
}
