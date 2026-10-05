package com.smsapp.timetable;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One period of a section's week; {@code dayOfWeek} is 1 (Monday) to 7 (Sunday). */
@Entity
@Table(name = "timetable_entries")
@Getter
@Setter
@NoArgsConstructor
public class TimetableEntry extends UuidEntity {

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "subject_group_id", nullable = false)
    private UUID subjectGroupId;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "time_from", nullable = false)
    private LocalTime timeFrom;

    @Column(name = "time_to", nullable = false)
    private LocalTime timeTo;

    @Column(name = "staff_profile_id")
    private UUID staffProfileId;

    @Column(name = "room_no", length = 30)
    private String roomNo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
