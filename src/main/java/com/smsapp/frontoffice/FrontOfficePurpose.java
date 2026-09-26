package com.smsapp.frontoffice;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** A configurable visit purpose (Parent Teacher Meeting, School Events, ...) used by the Visitor Book -- a lookup table, like {@link EnquirySource}. */
@Entity
@Table(name = "front_office_purposes")
@Getter
@Setter
@NoArgsConstructor
public class FrontOfficePurpose extends UuidEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
