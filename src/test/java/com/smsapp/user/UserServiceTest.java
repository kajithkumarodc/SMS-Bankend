package com.smsapp.user;

import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.user.UserDtos.CreateUserRequest;
import com.smsapp.user.UserDtos.CreateUserResponse;
import com.smsapp.user.UserDtos.UpdateUserRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    private final UUID roleId = UUID.randomUUID();

    private UserService service() {
        return new UserService(userRepository, roleRepository, userRoleRepository, passwordEncoder, auditService);
    }

    private static User user(UUID id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setFullName("Someone");
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    @Test
    void createsUserWithAGeneratedPasswordWhenNoneGiven() {
        when(userRepository.existsByEmail("new.teacher@school.example")).thenReturn(false);
        when(roleRepository.findAllById(Set.of(roleId))).thenReturn(List.of(role(roleId)));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(roleRepository.findNamesByUserId(any())).thenReturn(List.of("TEACHER"));

        CreateUserResponse response = service().create(
                new CreateUserRequest("new.teacher@school.example", "New Teacher", null, Set.of(roleId)));

        assertThat(response.generatedPassword()).isNotBlank();
        assertThat(response.user().mustChangePassword()).isTrue();
        assertThat(response.user().roles()).containsExactly("TEACHER");
        verify(userRoleRepository).saveAll(any());
    }

    @Test
    void createsUserWithAnAdminChosenPasswordAndDoesNotReturnIt() {
        when(userRepository.existsByEmail("new.teacher@school.example")).thenReturn(false);
        when(roleRepository.findAllById(Set.of(roleId))).thenReturn(List.of(role(roleId)));
        when(passwordEncoder.encode("Sup3rSecret!")).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(roleRepository.findNamesByUserId(any())).thenReturn(List.of("TEACHER"));

        CreateUserResponse response = service().create(
                new CreateUserRequest("new.teacher@school.example", "New Teacher", "Sup3rSecret!", Set.of(roleId)));

        assertThat(response.generatedPassword()).isNull();
    }

    @Test
    void rejectsDuplicateEmailWith409() {
        when(userRepository.existsByEmail("dup@school.example")).thenReturn(true);

        assertThatThrownBy(() -> service().create(
                new CreateUserRequest("dup@school.example", "Someone", null, Set.of(roleId))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsUnknownRoleIdWith400() {
        when(userRepository.existsByEmail("x@school.example")).thenReturn(false);
        when(roleRepository.findAllById(Set.of(roleId))).thenReturn(List.of());

        assertThatThrownBy(() -> service().create(
                new CreateUserRequest("x@school.example", "Someone", null, Set.of(roleId))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resetPasswordGeneratesANewOneAndFlagsMustChange() {
        UUID id = UUID.randomUUID();
        User u = user(id, "x@school.example");
        u.setMustChangePassword(false);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        var response = service().resetPassword(id);

        assertThat(response.temporaryPassword()).isNotBlank();
        assertThat(u.isMustChangePassword()).isTrue();
    }

    @Test
    void resetPasswordRejectsUnknownUserWith404() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().resetPassword(id))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void changeOwnPasswordRejectsWrongCurrentPasswordWith400() {
        UUID id = UUID.randomUUID();
        User u = user(id, "x@school.example");
        u.setPasswordHash("hashed-old");
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("wrong", "hashed-old")).thenReturn(false);

        assertThatThrownBy(() -> service().changeOwnPassword(id, "wrong", "newPassword1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(userRepository, never()).save(any());
    }

    @Test
    void changeOwnPasswordClearsMustChangeFlagOnSuccess() {
        UUID id = UUID.randomUUID();
        User u = user(id, "x@school.example");
        u.setPasswordHash("hashed-old");
        u.setMustChangePassword(true);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("correct", "hashed-old")).thenReturn(true);
        when(passwordEncoder.encode("newPassword1")).thenReturn("hashed-new");

        service().changeOwnPassword(id, "correct", "newPassword1");

        assertThat(u.isMustChangePassword()).isFalse();
        assertThat(u.getPasswordHash()).isEqualTo("hashed-new");
    }

    @Test
    void updateRejectsInvalidStatusWith400() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.of(user(id, "x@school.example")));

        assertThatThrownBy(() -> service().update(id,
                new UpdateUserRequest("Someone", "NOT_A_STATUS", Set.of(roleId))))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static Role role(UUID id) {
        Role r = new Role();
        r.setId(id);
        r.setName("TEACHER");
        return r;
    }
}
