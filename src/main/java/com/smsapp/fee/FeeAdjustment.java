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

/** A non-payment change to an invoice's amount (V41): an admission-time adjustment or a retroactive fee change. */
@Entity
@Table(name = "fee_adjustments")
@Getter
@Setter
@NoArgsConstructor
public class FeeAdjustment extends UuidEntity {

    public static final String ADMISSION_ADJUSTMENT = "ADMISSION_ADJUSTMENT";
    public static final String STRUCTURE_CHANGE = "STRUCTURE_CHANGE";

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "old_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal oldAmount;

    @Column(name = "new_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal newAmount;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
