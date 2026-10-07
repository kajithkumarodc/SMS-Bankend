package com.smsapp.calendar;

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

/** One entry of the annual calendar: a holiday, vacation, activity or event over a range of dates. */
@Entity
@Table(name = "calendar_events")
@Getter
@Setter
@NoArgsConstructor
public class CalendarEvent extends UuidEntity {

    @Column(name = "holiday_type_id", nullable = false)
    private UUID holidayTypeId;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "front_site", nullable = false)
    private boolean frontSite;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
