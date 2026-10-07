package com.smsapp.expense;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One school expense (V43). */
@Entity
@Table(name = "expenses")
@Getter
@Setter
@NoArgsConstructor
public class Expense extends UuidEntity {

    @Column(name = "expense_head_id", nullable = false)
    private UUID expenseHeadId;

    /** Read-only view of {@link #expenseHeadId}, mapped only so the list can sort by head name. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_head_id", insertable = false, updatable = false)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private ExpenseHead expenseHead;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column
    private String description;

    @Column(name = "attachment_original_filename")
    private String attachmentOriginalFilename;

    @Column(name = "attachment_stored_filename", length = 100)
    private String attachmentStoredFilename;

    @Column(name = "attachment_content_type", length = 150)
    private String attachmentContentType;

    @Column(name = "attachment_size_bytes")
    private Long attachmentSizeBytes;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public boolean hasAttachment() {
        return attachmentStoredFilename != null;
    }
}
