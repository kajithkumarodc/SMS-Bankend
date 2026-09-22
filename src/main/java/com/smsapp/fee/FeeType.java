package com.smsapp.fee;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * A configurable fee type (Tuition Fee, Transport Fee, ...) -- a lookup table, not a hardcoded enum
 * (plan Phase 5 part B: "Administrators should be able to configure fee types"). Optional tag on a
 * {@link FeeStructureItem}; same "lookup table, not a fixed set" precedent as
 * {@code com.smsapp.frontoffice.EnquirySource}.
 */
@Entity
@Table(name = "fee_types")
@Getter
@Setter
@NoArgsConstructor
public class FeeType extends UuidEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
