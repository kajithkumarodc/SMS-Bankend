package com.smsapp.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {

    List<CalendarEvent> findAllByOrderByFromDateDescCreatedAtDesc();

    List<CalendarEvent> findByHolidayTypeIdOrderByFromDateDescCreatedAtDesc(UUID holidayTypeId);

    boolean existsByHolidayTypeId(UUID holidayTypeId);
}
