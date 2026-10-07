package com.smsapp.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StaffAttendanceRepository extends JpaRepository<StaffAttendance, UUID> {

    List<StaffAttendance> findByStaffProfileIdAndAttendanceDateBetween(UUID staffProfileId, LocalDate from, LocalDate to);

    List<StaffAttendance> findByAttendanceDateAndStaffProfileIdIn(LocalDate attendanceDate, Collection<UUID> staffProfileIds);
}
