package com.smsapp.frontoffice;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One Phone Call Log entry (V33). */
@Entity
@Table(name = "phone_call_logs")
@Getter
@Setter
@NoArgsConstructor
public class PhoneCallLog extends UuidEntity {

    @Column(length = 200)
    private String name;

    @Column(nullable = false, length = 30)
    private String phone;

    @Column(name = "call_date", nullable = false)
    private LocalDate callDate;

    @Column
    private String description;

    @Column(name = "next_follow_up_date")
    private LocalDate nextFollowUpDate;

    /** Free text as the desk records it ("5 min", "00:12:30"). */
    @Column(name = "call_duration", length = 50)
    private String callDuration;

    @Column
    private String note;

    /** {@link CallType#INCOMING} or {@link CallType#OUTGOING}. */
    @Column(name = "call_type", nullable = false, length = 10)
    private String callType;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
