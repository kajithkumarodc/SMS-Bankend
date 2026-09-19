package com.smsapp.hostel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HostelBlockRepository extends JpaRepository<HostelBlock, UUID> {

    List<HostelBlock> findAllByOrderByName();
}
