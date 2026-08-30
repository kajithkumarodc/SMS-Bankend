package com.smsapp.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "user_roles")
@IdClass(UserRole.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class UserRole {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "role_id")
    private UUID roleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    public record Key(UUID userId, UUID roleId) implements Serializable {
    }
}
