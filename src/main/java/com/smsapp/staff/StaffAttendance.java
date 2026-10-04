package com.smsapp.staff;

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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One staff member's attendance mark for one day (V45). */
@Entity
@Table(name = "staff_attendance")
@Getter
@Setter
@NoArgsConstructor
public class StaffAttendance extends UuidEntity {

    @Column(name = "staff_profile_id", nullable = false)
    private UUID staffProfileId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    /** One of {@link StaffAttendanceStatus}'s names. */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "entry_time")
    private LocalTime entryTime;

    @Column(name = "exit_time")
    private LocalTime exitTime;

    @Column(length = 500)
    private String note;

    @Column(name = "marked_by_user_id")
    private UUID markedByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
