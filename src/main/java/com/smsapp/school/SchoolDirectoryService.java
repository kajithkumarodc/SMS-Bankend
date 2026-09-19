package com.smsapp.school;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class SchoolDirectoryService {

    private final SchoolRepository schoolRepository;

    public SchoolDirectoryService(SchoolRepository schoolRepository) {
        this.schoolRepository = schoolRepository;
    }

    @Transactional(readOnly = true)
    public List<SchoolSummary> list() {
        return schoolRepository.findAllByOrderByName().stream()
                .map(school -> new SchoolSummary(school.getId(), school.getName()))
                .toList();
    }

    public record SchoolSummary(UUID id, String name) {
    }
}
