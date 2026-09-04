package com.smsapp.fee;

import com.smsapp.common.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A named fee a school charges (e.g. "Term 1 Tuition"): an amount plus a due date. */
@Entity
@Table(name = "fee_structures")
@Getter
@Setter
@NoArgsConstructor
public class FeeStructure extends TenantScopedEntity {

    @Column(name = "school_id", nullable = false, updatable = false)
    private UUID schoolId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
