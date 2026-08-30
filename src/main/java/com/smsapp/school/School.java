package com.smsapp.school;

import com.smsapp.common.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "schools")
@Getter
@Setter
@NoArgsConstructor
public class School extends TenantScopedEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
