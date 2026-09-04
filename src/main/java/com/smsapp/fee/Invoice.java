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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One student's bill for one {@link FeeStructure}. Starts {@code PENDING}; the
 * Razorpay payment webhook (signature-verified) moves it to {@code PAID}. We
 * store only the gateway's safe order/payment reference ids -- never card or
 * bank details (plan section 7.2a).
 */
@Entity
@Table(name = "invoices")
@Getter
@Setter
@NoArgsConstructor
public class Invoice extends TenantScopedEntity {

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "fee_structure_id", nullable = false, updatable = false)
    private UUID feeStructureId;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "razorpay_order_id", length = 255)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id", length = 255)
    private String razorpayPaymentId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;
}
