package com.smsapp.attendance;

import com.smsapp.common.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "attendance_records")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceRecord extends TenantScopedEntity {

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(nullable = false, updatable = false)
    private LocalDate date;

    @Column(nullable = false, length = 20)
    private String status;

    /** The user (teacher or admin) who last marked this record. */
    @Column(name = "marked_by", nullable = false)
    private UUID markedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
