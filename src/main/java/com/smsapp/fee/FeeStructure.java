package com.smsapp.fee;

import com.smsapp.common.UuidEntity;
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
public class FeeStructure extends UuidEntity {

    @Column(name = "school_id", nullable = false, updatable = false)
    private UUID schoolId;

    /** The class this structure applies to, or null to apply to every class (e.g. a school-wide fee). */
    @Column(name = "class_id")
    private UUID classId;

    /** E.g. "2026-2027". Null for structures created before this field existed. */
    @Column(name = "academic_year", length = 20)
    private String academicYear;

    @Column(nullable = false, length = 150)
    private String name;

    /**
     * The total payable amount. Authoritative: kept in sync with the sum of this
     * structure's {@link FeeStructureItem}s whenever any exist, or set directly
     * for the backward-compatible flat-amount case (zero items).
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** One of {@link FeeFrequency}. */
    @Column(nullable = false, length = 20)
    private String frequency = FeeFrequency.ONE_TIME;

    /** Optional flat late fee, applied once (see {@code Invoice.lateFeeApplied}) to an overdue invoice. */
    @Column(name = "late_fee_amount", precision = 12, scale = 2)
    private BigDecimal lateFeeAmount;

    /** One of {@link FeeStructureStatus}. An INACTIVE structure is kept (invoices may still reference it) but hidden from new assignment pickers. */
    @Column(nullable = false, length = 20)
    private String status = FeeStructureStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
