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
 * One fee line of a student's bill (V42): a fee type in a term, e.g. "Tuition Fee, Term I, due 10 Jun, 11,785".
 * The bill's {@link Invoice#getAmount()} is the sum of its lines' amounts, and its discount the sum of theirs.
 */
@Entity
@Table(name = "invoice_lines")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceLine extends UuidEntity {

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(nullable = false, length = 150)
    private String label;

    @Column(name = "fee_type_id")
    private UUID feeTypeId;

    /** TERM_1..TERM_4 or another fee-line category (the term this line belongs to). */
    @Column(nullable = false, length = 20)
    private String category;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
