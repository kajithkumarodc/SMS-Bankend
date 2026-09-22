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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line item of a {@link FeeStructure} (e.g. "Application Fees: 100",
 * "Term 2 Fees: 4400"), matching how a real school fee-structure sheet breaks
 * a class's total into named categories.
 */
@Entity
@Table(name = "fee_structure_items")
@Getter
@Setter
@NoArgsConstructor
public class FeeStructureItem extends UuidEntity {

    @Column(name = "fee_structure_id", nullable = false, updatable = false)
    private UUID feeStructureId;

    /** One of {@link FeeStructureItemCategory}'s constants; enforced by a DB CHECK constraint (V21). */
    @Column(nullable = false, length = 20)
    private String category;

    /** Optional display name distinguishing items that share a category (e.g. two TERM_1 items). */
    @Column(length = 150)
    private String label;

    /** Optional tag into the configurable {@link FeeType} catalog, for reporting/filtering -- orthogonal to {@link #category}. */
    @Column(name = "fee_type_id")
    private UUID feeTypeId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
