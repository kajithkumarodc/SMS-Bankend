package com.smsapp.auth;

import com.smsapp.user.RoleRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    @Test
    void loginReturnsJwtForValidCredentials() {
        UUID tenantId = UUID.randomUUID();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenantId);
        user.setEmail("admin@example.com");
        user.setPasswordHash("encoded");

        when(userRepository.findByTenantIdAndEmail(tenantId, user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "encoded")).thenReturn(true);
        when(roleRepository.findNamesByUserId(user.getId())).thenReturn(List.of("SCHOOL_ADMIN"));
        when(entityManager.createNativeQuery(any())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(any(String.class), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn(tenantId.toString());
        var jwt = org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.Jwt.class);
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);
        when(jwt.getTokenValue()).thenReturn("token");

        var response = new AuthService(userRepository, roleRepository, passwordEncoder, jwtEncoder, entityManager)
                .login(new AuthService.LoginRequest(tenantId.toString(), user.getEmail(), "secret"));

        assertThat(response.token()).isNotBlank();
    }

    @Test
    void loginRejectsInvalidPassword() {
        UUID tenantId = UUID.randomUUID();
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenantId(tenantId);
        user.setEmail("admin@example.com");
        user.setPasswordHash("encoded");

        when(userRepository.findByTenantIdAndEmail(eq(tenantId), eq(user.getEmail()))).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        when(entityManager.createNativeQuery(any())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(any(String.class), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn(tenantId.toString());

        assertThatThrownBy(() -> new AuthService(userRepository, roleRepository, passwordEncoder, jwtEncoder, entityManager)
                .login(new AuthService.LoginRequest(tenantId.toString(), user.getEmail(), "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }
}
