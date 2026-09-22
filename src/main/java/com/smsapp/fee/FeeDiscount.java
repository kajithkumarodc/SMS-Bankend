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

/**
 * A reusable, admin-configurable discount rule (e.g. "Sibling Discount 10%", "Staff Ward Waiver
 * 500"). Applying one to an {@link Invoice} is a deliberate staff action
 * ({@link FeeCollectionService#applyDiscount}) that snapshots the computed amount onto the
 * invoice -- this row is the reusable definition, not a per-invoice record.
 */
@Entity
@Table(name = "fee_discounts")
@Getter
@Setter
@NoArgsConstructor
public class FeeDiscount extends UuidEntity {

    @Column(nullable = false, length = 150)
    private String name;

    /** One of {@link FeeDiscountType}. */
    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType;

    /** A currency amount when {@code discountType} is FIXED, or a 0-100 percentage when PERCENTAGE. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal value;

    /** Optional: restricts this discount to invoices raised against one fee structure. Null = usable on any invoice. */
    @Column(name = "fee_structure_id")
    private UUID feeStructureId;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    /** One of {@link FeeStructureStatus} (ACTIVE/INACTIVE) -- same two-state lifecycle, reused rather than a new constant class. */
    @Column(nullable = false, length = 20)
    private String status = FeeStructureStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
