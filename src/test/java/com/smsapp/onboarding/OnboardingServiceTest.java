package com.smsapp.onboarding;

import com.smsapp.common.ApiException;
import com.smsapp.onboarding.OnboardingDtos.RegisterSchoolRequest;
import com.smsapp.onboarding.OnboardingDtos.RegisterSchoolResponse;
import com.smsapp.school.School;
import com.smsapp.school.SchoolRepository;
import com.smsapp.tenant.Tenant;
import com.smsapp.tenant.TenantContext;
import com.smsapp.tenant.TenantRepository;
import com.smsapp.user.Role;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.Roles;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import com.smsapp.user.UserRole;
import com.smsapp.user.UserRoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private SchoolRepository schoolRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    private OnboardingService service() {
        return new OnboardingService(tenantRepository, schoolRepository, roleRepository, userRepository,
                userRoleRepository, passwordEncoder, entityManager);
    }

    private static RegisterSchoolRequest request() {
        return new RegisterSchoolRequest("Springfield Elementary", "Springfield", "Seymour Skinner",
                "Admin@Springfield.example", "Sup3rSecret");
    }

    @AfterEach
    void clearTenantContext() {
        // registerSchool sets this as a side effect, same as AuthService#login -- don't leak
        // it into other tests on a shared thread (the surefire test-runner thread pool).
        TenantContext.clear();
    }

    private void stubNativeQuery() {
        when(entityManager.createNativeQuery(any())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(any(String.class), any())).thenReturn(nativeQuery);
        when(nativeQuery.getSingleResult()).thenReturn("");
    }

    @Test
    void registerSchoolCreatesTenantSchoolRoleAndAdminAndAudits() {
        when(tenantRepository.findByIdentifier("springfield")).thenReturn(Optional.empty());
        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        when(tenantRepository.saveAndFlush(tenantCaptor.capture())).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });
        stubNativeQuery();
        ArgumentCaptor<School> schoolCaptor = ArgumentCaptor.forClass(School.class);
        when(schoolRepository.save(schoolCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        when(roleRepository.save(roleCaptor.capture())).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(userRepository.findByTenantIdAndEmail(any(), eq("admin@springfield.example"))).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Sup3rSecret")).thenReturn("hashed");
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        when(userRepository.saveAndFlush(userCaptor.capture())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        ArgumentCaptor<UserRole> userRoleCaptor = ArgumentCaptor.forClass(UserRole.class);
        when(userRoleRepository.save(userRoleCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        RegisterSchoolResponse response = service().registerSchool(request());

        Tenant tenant = tenantCaptor.getValue();
        assertThat(tenant.getName()).isEqualTo("Springfield Elementary");
        assertThat(tenant.getIdentifier()).isEqualTo("springfield"); // normalised to lower-case

        School school = schoolCaptor.getValue();
        assertThat(school.getTenantId()).isEqualTo(tenant.getId());
        assertThat(school.getName()).isEqualTo("Springfield Elementary");

        Role role = roleCaptor.getValue();
        assertThat(role.getTenantId()).isEqualTo(tenant.getId());
        assertThat(role.getName()).isEqualTo(Roles.SCHOOL_ADMIN);

        User admin = userCaptor.getValue();
        assertThat(admin.getTenantId()).isEqualTo(tenant.getId());
        assertThat(admin.getEmail()).isEqualTo("admin@springfield.example"); // normalised to lower-case
        assertThat(admin.getFullName()).isEqualTo("Seymour Skinner");
        assertThat(admin.getPasswordHash()).isEqualTo("hashed"); // never the raw password
        assertThat(admin.getStatus()).isEqualTo("ACTIVE");

        UserRole userRole = userRoleCaptor.getValue();
        assertThat(userRole.getUserId()).isEqualTo(admin.getId());
        assertThat(userRole.getRoleId()).isEqualTo(role.getId());
        assertThat(userRole.getTenantId()).isEqualTo(tenant.getId());

        assertThat(response.tenantId()).isEqualTo(tenant.getId());
        assertThat(response.schoolIdentifier()).isEqualTo("springfield");
        assertThat(response.adminEmail()).isEqualTo("admin@springfield.example");
        assertThat(response.adminUserId()).isEqualTo(admin.getId());

        assertThat(TenantContext.getCurrentTenant()).isEqualTo(tenant.getId().toString());
    }

    @Test
    void registerSchoolWithATakenIdentifierReturns409() {
        when(tenantRepository.findByIdentifier("springfield")).thenReturn(Optional.of(new Tenant()));

        assertThatThrownBy(() -> service().registerSchool(request()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(tenantRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerSchoolTranslatesAConcurrentIdentifierRaceIntoAClean409() {
        when(tenantRepository.findByIdentifier("springfield")).thenReturn(Optional.empty());
        when(tenantRepository.saveAndFlush(any(Tenant.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service().registerSchool(request()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(schoolRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerSchoolWithAnAdminEmailAlreadyInTheNewTenantReturns409() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findByIdentifier("springfield")).thenReturn(Optional.empty());
        when(tenantRepository.saveAndFlush(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(tenantId);
            return t;
        });
        stubNativeQuery();
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(userRepository.findByTenantIdAndEmail(tenantId, "admin@springfield.example"))
                .thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service().registerSchool(request()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(userRepository, never()).saveAndFlush(any());
        verify(userRoleRepository, never()).save(any());
    }

    @Test
    void registerSchoolTranslatesAConcurrentEmailRaceIntoAClean409() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findByIdentifier("springfield")).thenReturn(Optional.empty());
        when(tenantRepository.saveAndFlush(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(tenantId);
            return t;
        });
        stubNativeQuery();
        when(schoolRepository.save(any(School.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
            Role r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(userRepository.findByTenantIdAndEmail(tenantId, "admin@springfield.example")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service().registerSchool(request()))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(userRoleRepository, never()).save(any());
    }
}
