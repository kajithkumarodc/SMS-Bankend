package com.smsapp.frontoffice;

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

/** One follow-up history entry against an {@link AdmissionEnquiry}. */
@Entity
@Table(name = "enquiry_follow_ups")
@Getter
@Setter
@NoArgsConstructor
public class EnquiryFollowUp extends UuidEntity {

    @Column(name = "enquiry_id", nullable = false)
    private UUID enquiryId;

    @Column(name = "follow_up_date", nullable = false)
    private LocalDate followUpDate;

    @Column(name = "follow_up_type", nullable = false, length = 20)
    private String followUpType;

    @Column
    private String notes;

    @Column(name = "staff_user_id")
    private UUID staffUserId;

    @Column(name = "next_follow_up_date")
    private LocalDate nextFollowUpDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
