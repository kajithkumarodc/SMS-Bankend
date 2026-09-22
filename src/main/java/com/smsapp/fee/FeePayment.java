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
 * One row in the append-only fee payment ledger -- every collection (manual or the existing
 * Razorpay online path) and every reversal (plan Phase 5 part G) flows through here, so
 * collected-income figures are computed one way, never double-counted. Nothing here is ever
 * updated or deleted: a reversal is a new row of {@code type} REVERSAL referencing the PAYMENT row
 * it reverses via {@link #reversesPaymentId}.
 */
@Entity
@Table(name = "fee_payments")
@Getter
@Setter
@NoArgsConstructor
public class FeePayment extends UuidEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    /** One of {@link PaymentType}. */
    @Column(nullable = false, length = 20, updatable = false)
    private String type = PaymentType.PAYMENT;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    /** One of {@link PaymentMethod}. */
    @Column(nullable = false, length = 20, updatable = false)
    private String method;

    /** Cheque number / bank transaction reference / Razorpay payment id, as applicable. */
    @Column(name = "reference_number", length = 255, updatable = false)
    private String referenceNumber;

    @Column(name = "receipt_number", nullable = false, length = 30, updatable = false)
    private String receiptNumber;

    /** Who collected this payment; null for the Razorpay webhook / dev-simulate path (no human actor). */
    @Column(name = "collected_by_user_id", updatable = false)
    private UUID collectedByUserId;

    @Column(length = 500, updatable = false)
    private String notes;

    /** Set only on a REVERSAL row: the PAYMENT row it reverses. */
    @Column(name = "reverses_payment_id", updatable = false)
    private UUID reversesPaymentId;

    /** Required on a REVERSAL row. */
    @Column(length = 500, updatable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "paid_at", nullable = false, updatable = false)
    private OffsetDateTime paidAt;
}
