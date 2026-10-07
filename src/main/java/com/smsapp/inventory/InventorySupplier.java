package com.smsapp.inventory;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** Someone items are bought from. */
@Entity
@Table(name = "inventory_suppliers")
@Getter
@Setter
@NoArgsConstructor
public class InventorySupplier extends UuidEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 30)
    private String phone;

    @Column(length = 150)
    private String email;

    @Column(length = 300)
    private String address;

    @Column(name = "contact_person_name", length = 100)
    private String contactPersonName;

    @Column(name = "contact_person_phone", length = 30)
    private String contactPersonPhone;

    @Column(name = "contact_person_email", length = 150)
    private String contactPersonEmail;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
