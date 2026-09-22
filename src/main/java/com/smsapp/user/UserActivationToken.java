package com.smsapp.user;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A one-time, unguessable token that lets a newly-created user set their own password via an
 * emailed link (plan Phase 4.5 part 14) -- an addition to the existing "admin sees a temporary
 * password once" flow ({@link UserService#create}), not a replacement for it: this is specifically
 * for inviting a user who cannot be handed a password in person (an online-admission parent).
 */
@Entity
@Table(name = "user_activation_tokens")
@Getter
@Setter
@NoArgsConstructor
public class UserActivationToken extends UuidEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 100, updatable = false)
    private String token;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
