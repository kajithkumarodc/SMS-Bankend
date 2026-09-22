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
 * One student's bill for one {@link FeeStructure} -- the fee "assignment/allotment" of plan Phase
 * 5 part C. Starts {@code PENDING}; every payment against it (manual, via {@link FeeCollectionService},
 * or the Razorpay webhook, still signature-verified) is recorded as its own {@link FeePayment} row
 * and this invoice's {@code paidAmount}/{@code status} are updated transactionally alongside it, so
 * the two never drift. We store only the gateway's safe order/payment reference ids -- never card or
 * bank details (plan section 7.2a).
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
public class Invoice extends UuidEntity {

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "fee_structure_id", nullable = false, updatable = false)
    private UUID feeStructureId;

    /** The gross amount copied from the fee structure at creation time -- never changes afterward. */
    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "razorpay_order_id", length = 255)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 255)
    private String razorpayPaymentId;

    /** The {@link FeeDiscount} applied, if any -- informational/traceability; {@link #discountAmount} is authoritative. */
    @Column(name = "discount_id")
    private UUID discountId;

    /** Snapshot of the computed discount at the time it was applied -- never recomputed from a live join. */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** The authoritative payable amount every payment is validated against: amount - discountAmount + lateFeeAmount. */
    @Column(name = "net_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "late_fee_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal lateFeeAmount = BigDecimal.ZERO;

    /** Whether the structure's configured late fee has already been folded into {@link #netAmount} (applied at most once). */
    @Column(name = "late_fee_applied", nullable = false)
    private boolean lateFeeApplied;

    /** Sum of non-reversed {@link FeePayment}s against this invoice. Maintained transactionally, never a separate source of truth. */
    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** Who assigned this fee to the student (created the invoice); null for pre-Phase-5 rows. */
    @Column(name = "assigned_by_user_id")
    private UUID assignedByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    /** balance = netAmount - paidAmount, never negative (payments are validated to never exceed it). */
    @jakarta.persistence.Transient
    public BigDecimal getBalance() {
        return netAmount.subtract(paidAmount);
    }
}
