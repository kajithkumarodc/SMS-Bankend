package com.smsapp.audit;

import com.smsapp.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private AuditService service() {
        return new AuditService(auditLogRepository);
    }

    private void authenticateAs(UUID userId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(userId.toString())
                .claim("scope", "test").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void logIsANoOpWhenThereIsNoTenantContext() {
        service().log(AuditActions.STUDENT_CREATED, AuditActions.STUDENT, UUID.randomUUID(), Map.of());

        verifyNoInteractions(auditLogRepository);
    }

    @Test
    void logTakesTenantAndActorFromTheCurrentContext() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId.toString());
        authenticateAs(actorId);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service().log(AuditActions.STUDENT_CREATED, AuditActions.STUDENT, entityId, Map.of("admissionNumber", "ADM-1"));

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getValue().getActorUserId()).isEqualTo(actorId);
        assertThat(saved.getValue().getAction()).isEqualTo(AuditActions.STUDENT_CREATED);
        assertThat(saved.getValue().getEntityType()).isEqualTo(AuditActions.STUDENT);
        assertThat(saved.getValue().getEntityId()).isEqualTo(entityId);
        assertThat(saved.getValue().getDetails()).containsEntry("admissionNumber", "ADM-1");
    }

    @Test
    void recordUsesTheExplicitTenantAndActorEvenWithNoSecurityContext() {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service().logAs(tenantId, actorId, AuditActions.LOGIN_FAILED, AuditActions.USER, entityId,
                Map.of("reason", "bad_password"));

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getValue().getActorUserId()).isEqualTo(actorId);
        assertThat(saved.getValue().getAction()).isEqualTo(AuditActions.LOGIN_FAILED);
        assertThat(saved.getValue().getDetails()).containsEntry("reason", "bad_password");
    }

    @Test
    void recordAllowsANullActorForUnattributableActions() {
        UUID tenantId = UUID.randomUUID();
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));

        service().logAs(tenantId, null, AuditActions.LOGIN_FAILED, AuditActions.USER, null,
                Map.of("reason", "user_not_found"));

        ArgumentCaptor<AuditLog> saved = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(saved.capture());
        assertThat(saved.getValue().getActorUserId()).isNull();
        assertThat(saved.getValue().getEntityId()).isNull();
    }
}
