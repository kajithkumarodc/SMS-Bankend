package com.smsapp.inventory;

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

/** Units bought in (or, when negative, taken out) of an item, with who supplied them and where they went. */
@Entity
@Table(name = "inventory_stock_entries")
@Getter
@Setter
@NoArgsConstructor
public class InventoryStockEntry extends UuidEntity {

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "store_id")
    private UUID storeId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "purchase_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(length = 500)
    private String description;

    @Column(name = "attachment_original_filename")
    private String attachmentOriginalFilename;

    @Column(name = "attachment_stored_filename", length = 100)
    private String attachmentStoredFilename;

    @Column(name = "attachment_content_type", length = 150)
    private String attachmentContentType;

    @Column(name = "attachment_size_bytes")
    private Long attachmentSizeBytes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public boolean hasAttachment() {
        return attachmentStoredFilename != null;
    }
}
