package com.smsapp.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HolidayTypeRepository extends JpaRepository<HolidayType, UUID> {

    List<HolidayType> findAllByOrderByName();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
