package com.smsapp.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@MappedSuperclass
public abstract class TenantScopedEntity extends UuidEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
}
