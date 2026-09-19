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
    private AuditService auditService;

    private AuthService newService() {
        return new AuthService(userRepository, roleRepository, passwordEncoder, jwtEncoder, auditService);
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
        var jwt = org.mockito.Mockito.mock(org.springframework.security.oauth2.jwt.Jwt.class);
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(jwt);
        when(jwt.getTokenValue()).thenReturn("token");

        var response = newService().login(new AuthService.LoginRequest(user.getEmail(), "secret"));

        assertThat(response.token()).isNotBlank();
        assertThat(response.user().id()).isEqualTo(user.getId().toString());
        assertThat(response.user().name()).isEqualTo("Admin User");
        assertThat(response.user().roles()).containsExactly("SCHOOL_ADMIN");
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
}
