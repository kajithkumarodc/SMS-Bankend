package com.smsapp.inventory;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Some units of an item issued to a staff member, until they are returned. */
@Entity
@Table(name = "inventory_issues")
@Getter
@Setter
@NoArgsConstructor
public class InventoryIssue extends UuidEntity {

    public static final String ISSUED = "ISSUED";
    public static final String RETURNED = "RETURNED";

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "issue_to_staff_id", nullable = false)
    private UUID issueToStaffId;

    @Column(name = "issued_by_staff_id", nullable = false)
    private UUID issuedByStaffId;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 20)
    private String status = ISSUED;

    @Column(name = "returned_at")
    private OffsetDateTime returnedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
