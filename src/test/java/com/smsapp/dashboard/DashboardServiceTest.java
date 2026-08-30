package com.smsapp.dashboard;

import com.smsapp.school.SchoolRepository;
import com.smsapp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private SchoolRepository schoolRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    void schoolAdminGetsRealTenantScopedCounts() {
        UUID tenantId = UUID.randomUUID();
        when(schoolRepository.countByTenantId(tenantId)).thenReturn(4L);
        when(userRepository.countByTenantId(tenantId)).thenReturn(12L);

        DashboardSummary summary = new DashboardService(schoolRepository, userRepository)
                .summaryFor(tenantId, "user-1", List.of("SCHOOL_ADMIN"));

        assertThat(summary.placeholder()).isFalse();
        assertThat(summary.note()).isNull();
        assertThat(summary.counts()).isEqualTo(new DashboardSummary.Counts(4L, 12L));
        assertThat(summary.tenantId()).isEqualTo(tenantId.toString());
        verify(schoolRepository).countByTenantId(tenantId);
        verify(userRepository).countByTenantId(tenantId);
    }

    @Test
    void nonAdminRoleGetsPlaceholderAndNoCounts() {
        UUID tenantId = UUID.randomUUID();

        DashboardSummary summary = new DashboardService(schoolRepository, userRepository)
                .summaryFor(tenantId, "user-2", List.of("TEACHER"));

        assertThat(summary.placeholder()).isTrue();
        assertThat(summary.counts()).isNull();
        assertThat(summary.note()).isNotBlank();
        assertThat(summary.roles()).containsExactly("TEACHER");
        verifyNoInteractions(schoolRepository, userRepository);
    }
}
