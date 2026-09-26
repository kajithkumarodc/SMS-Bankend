package com.smsapp.frontoffice;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One Postal Receive entry (V35). */
@Entity
@Table(name = "postal_receives")
@Getter
@Setter
@NoArgsConstructor
public class PostalReceive extends UuidEntity {

    /** PRC-YYYY-NNNNN, issued by {@link DocumentNumberGenerator} on creation and never changed. */
    @Column(name = "reference_no", nullable = false, length = 20, updatable = false)
    private String referenceNo;

    @Column(name = "from_title", nullable = false, length = 200)
    private String fromTitle;

    @Column(name = "to_title", length = 200)
    private String toTitle;

    @Column
    private String address;

    @Column
    private String note;

    @Column(name = "receive_date", nullable = false)
    private LocalDate receiveDate;

    @Column(name = "academic_year_id", updatable = false)
    private UUID academicYearId;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
