package com.smsapp.staff;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** A kind of leave staff can take (Medical Leave, Casual Leave, ...) -- a lookup table (V47). */
@Entity
@Table(name = "leave_types")
@Getter
@Setter
@NoArgsConstructor
public class LeaveType extends UuidEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
