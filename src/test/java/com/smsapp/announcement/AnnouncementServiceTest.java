package com.smsapp.announcement;

import com.smsapp.announcement.AnnouncementDtos.CreateAnnouncementRequest;
import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock
    private AnnouncementRepository announcementRepository;

    @Mock
    private AuditService auditService;

    private AnnouncementService service() {
        return new AnnouncementService(announcementRepository, auditService);
    }

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    @Test
    void createTrimsAndStampsTheAuthorAndTenant() {
        when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> {
            Announcement a = inv.getArgument(0);
            a.setId(id);
            return a;
        });

        Announcement created = service().create(tenantId, userId,
                new CreateAnnouncementRequest("  Sports day  ", "  Moved to Friday.  "));

        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getCreatedBy()).isEqualTo(userId);
        assertThat(created.getTitle()).isEqualTo("Sports day");
        assertThat(created.getBody()).isEqualTo("Moved to Friday.");
        verify(auditService).log(eq(AuditActions.ANNOUNCEMENT_CREATED), eq(AuditActions.ANNOUNCEMENT), eq(id), anyMap());
    }

    @Test
    void deleteRemovesTheRowAndAudits() {
        Announcement existing = new Announcement();
        existing.setId(id);
        existing.setTenantId(tenantId);
        existing.setTitle("Old notice");
        when(announcementRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(existing));

        service().delete(tenantId, id);

        verify(announcementRepository).delete(existing);
        verify(auditService).log(eq(AuditActions.ANNOUNCEMENT_DELETED), eq(AuditActions.ANNOUNCEMENT), eq(id), anyMap());
    }

    @Test
    void deleteIs404WhenNotInTenant() {
        when(announcementRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(tenantId, id))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(announcementRepository, never()).delete(any());
    }
}
