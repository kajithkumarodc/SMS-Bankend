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
 * How much of a {@link FeePayment} went to one {@link InvoiceLine} (V42): {@code amount} towards the fee and
 * {@code fineAmount} collected as a fine on it. A reversed payment's allocations no longer count.
 */
@Entity
@Table(name = "fee_payment_allocations")
@Getter
@Setter
@NoArgsConstructor
public class FeePaymentAllocation extends UuidEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "invoice_line_id", nullable = false, updatable = false)
    private UUID invoiceLineId;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "fine_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal fineAmount = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
