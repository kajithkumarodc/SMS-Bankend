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

/** Mirrors {@link UserRole}: a plain join row, no entity-level relationship mapping. */
@Entity
@Table(name = "role_permissions")
@IdClass(RolePermission.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class RolePermission {

    @Id
    @Column(name = "role_id")
    private UUID roleId;

    @Id
    @Column(name = "permission_id")
    private UUID permissionId;

    public RolePermission(UUID roleId, UUID permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    public record Key(UUID roleId, UUID permissionId) implements Serializable {
    }
}
