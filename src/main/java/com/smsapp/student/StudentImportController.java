package com.smsapp.student;

import com.smsapp.common.ApiException;
import com.smsapp.student.StudentImportService.ImportResult;
import com.smsapp.user.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Student Admission -> Import Student (CSV). SCHOOL_ADMIN only, like admitting a single student. */
@RestController
@RequestMapping("/api/v1/students/import")
public class StudentImportController {

    private final StudentImportService importService;

    public StudentImportController(StudentImportService importService) {
        this.importService = importService;
    }

    /** The column headers the CSV must start with (the sample file's first line). */
    @GetMapping("/columns")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    List<String> columns() {
        return StudentImportService.COLUMNS;
    }

    /** Imports every row into {@code sectionId}; each row is reported as IMPORTED or SKIPPED with reasons. */
    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize(Roles.HAS_SCHOOL_ADMIN)
    ImportResult importStudents(@RequestPart("file") MultipartFile file, @RequestParam UUID sectionId,
                                @RequestParam(required = false) UUID mediumId) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv")) {
            throw new ApiException("Upload a .csv file", HttpStatus.BAD_REQUEST);
        }
        return importService.importCsv(file.getBytes(), sectionId, mediumId);
    }
}
