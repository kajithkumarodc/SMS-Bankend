package com.smsapp.user;

import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.user.RoleDtos.RoleResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionRepository permissionRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @Mock
    private AuditService auditService;

    private RoleService service() {
        return new RoleService(roleRepository, permissionRepository, rolePermissionRepository, auditService);
    }

    private static Role role(UUID id, String name) {
        Role r = new Role();
        r.setId(id);
        r.setName(name);
        return r;
    }

    private static Permission permission(UUID id, String name) {
        Permission p = new Permission();
        p.setId(id);
        p.setName(name);
        return p;
    }

    @Test
    void createsRole() {
        when(roleRepository.existsByName("EXAM_COORDINATOR")).thenReturn(false);
        when(roleRepository.saveAndFlush(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Role created = service().create("  EXAM_COORDINATOR  ");

        assertThat(created.getName()).isEqualTo("EXAM_COORDINATOR");
    }

    @Test
    void rejectsDuplicateRoleNameWith409() {
        when(roleRepository.existsByName("TEACHER")).thenReturn(true);

        assertThatThrownBy(() -> service().create("TEACHER"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(roleRepository, never()).saveAndFlush(any());
    }

    @Test
    void listAllNestsPermissionNamesUnderTheirRole() {
        UUID roleId = UUID.randomUUID();
        UUID permId = UUID.randomUUID();
        when(roleRepository.findAllByOrderByName()).thenReturn(List.of(role(roleId, "LIBRARIAN")));
        when(permissionRepository.findAll()).thenReturn(List.of(permission(permId, "LIBRARY_VIEW")));
        when(rolePermissionRepository.findAll()).thenReturn(List.of(new RolePermission(roleId, permId)));

        List<RoleResponse> result = service().listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("LIBRARIAN");
        assertThat(result.get(0).permissionNames()).containsExactly("LIBRARY_VIEW");
    }

    @Test
    void replacePermissionsRejectsUnknownRoleWith404() {
        UUID roleId = UUID.randomUUID();
        when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().replacePermissions(roleId, Set.of()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void replacePermissionsRejectsUnknownPermissionIdWith400() {
        UUID roleId = UUID.randomUUID();
        UUID permId = UUID.randomUUID();
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role(roleId, "LIBRARIAN")));
        when(permissionRepository.findAllById(Set.of(permId))).thenReturn(List.of());

        assertThatThrownBy(() -> service().replacePermissions(roleId, Set.of(permId)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(rolePermissionRepository, never()).saveAll(any());
    }

    @Test
    void replacePermissionsDeletesThenReinsertsTheFullSet() {
        UUID roleId = UUID.randomUUID();
        UUID permId = UUID.randomUUID();
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role(roleId, "LIBRARIAN")));
        when(permissionRepository.findAllById(Set.of(permId))).thenReturn(List.of(permission(permId, "LIBRARY_VIEW")));

        service().replacePermissions(roleId, Set.of(permId));

        verify(rolePermissionRepository).deleteByRoleId(roleId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RolePermission>> captor = ArgumentCaptor.forClass(List.class);
        verify(rolePermissionRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getRoleId()).isEqualTo(roleId);
        assertThat(captor.getValue().get(0).getPermissionId()).isEqualTo(permId);
    }
}
