package com.smsapp.school;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only directory of the school(s). Exists so the frontend can offer a
 * school picker when enrolling a student; a fuller schools/campus module comes
 * later. Any authenticated role may read it.
 */
@RestController
@RequestMapping("/api/v1/schools")
public class SchoolController {

    private final SchoolDirectoryService schoolDirectoryService;

    public SchoolController(SchoolDirectoryService schoolDirectoryService) {
        this.schoolDirectoryService = schoolDirectoryService;
    }

    @GetMapping
    public List<SchoolDirectoryService.SchoolSummary> list() {
        return schoolDirectoryService.list();
    }
}
