package com.smsapp.student;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Student Information -> Bulk Delete: permanently removes student records that were created by mistake.
 *
 * <p>A student is only deletable while nothing else depends on the record -- no attendance, exam marks,
 * invoices, library loans or visitor-book entries. Those students are listed with the reason and must be
 * disabled instead, so financial and academic history is never lost. Identifications, documents and
 * academic history rows cascade with the student; "converted to student" links on enquiries and online
 * applications are cleared. Uploaded document files are removed from disk once the delete commits.
 */
@Service
public class StudentBulkDeleteService {

    private static final Logger log = LoggerFactory.getLogger(StudentBulkDeleteService.class);

    /** Upper bound on one request, matching the largest page the screen can show. */
    static final int MAX_PER_REQUEST = 500;

    /** Each dependency that blocks a hard delete, in the order its reason is reported. */
    private static final Map<String, String> BLOCKERS = blockers();

    private static Map<String, String> blockers() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("attendance_records", "attendance");
        map.put("exam_marks", "exam marks");
        map.put("invoices", "fee invoices");
        map.put("book_loans", "library loans");
        map.put("visitors", "visitor book entries");
        return map;
    }

    private static final String CANDIDATE_SELECT = """
            SELECT s.id, s.admission_number, s.full_name, c.name AS class_name,
                   CASE WHEN sec.is_default THEN NULL ELSE sec.name END AS section_name,
                   s.date_of_birth, s.gender, s.category, s.status,
                   COALESCE(NULLIF(s.guardian_phone, ''), NULLIF(s.father_mobile, ''), NULLIF(s.mother_mobile, ''))
                       AS mobile
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final Path baseDir;

    public StudentBulkDeleteService(NamedParameterJdbcTemplate jdbc, AuditService auditService,
                                    @Value("${app.storage.base-dir}") String baseDir) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    public record Candidate(UUID id, String admissionNumber, String fullName, String className,
                            String sectionName, LocalDate dateOfBirth, String gender, String category,
                            String status, String mobile, boolean deletable, String blockReason) {
    }

    public record Skipped(UUID id, String admissionNumber, String fullName, String reason) {
    }

    public record Deleted(UUID id, String admissionNumber, String fullName) {
    }

    public record Result(List<Deleted> deleted, List<Skipped> skipped) {
    }

    /**
     * Students in a class (optionally one section), each flagged with whether it can be deleted.
     *
     * @throws ApiException 404 if the class does not exist, or the section is not in that class.
     */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID classId, UUID sectionId) {
        MapSqlParameterSource params = new MapSqlParameterSource("classId", classId);
        if (!exists("SELECT EXISTS (SELECT 1 FROM classes WHERE id = :classId)", params)) {
            throw new ApiException("Class not found", HttpStatus.NOT_FOUND);
        }
        String sql = CANDIDATE_SELECT + """
                FROM students s
                JOIN sections sec ON sec.id = s.section_id
                JOIN classes c ON c.id = sec.class_id
                WHERE c.id = :classId
                """;
        if (sectionId != null) {
            params.addValue("sectionId", sectionId);
            if (!exists("SELECT EXISTS (SELECT 1 FROM sections WHERE id = :sectionId AND class_id = :classId)",
                    params)) {
                throw new ApiException("Section not found in this class", HttpStatus.NOT_FOUND);
            }
            sql += " AND sec.id = :sectionId";
        }
        sql += " ORDER BY lower(sec.name), lower(s.full_name), s.admission_number";

        List<Row> rows = jdbc.query(sql, params, StudentBulkDeleteService::mapRow);
        Map<UUID, String> reasons = blockReasons(rows.stream().map(Row::id).toList());
        return rows.stream().map(row -> {
            String reason = reasons.get(row.id());
            return new Candidate(row.id(), row.admissionNumber(), row.fullName(), row.className(),
                    row.sectionName(), row.dateOfBirth(), row.gender(), row.category(), row.status(),
                    row.mobile(), reason == null, reason);
        }).toList();
    }

    /**
     * Permanently deletes the given students. Ids that no longer exist, or whose record now has dependent
     * history, are returned in {@code skipped} with the reason rather than failing the whole request.
     *
     * @throws ApiException 400 if the list is empty or longer than {@link #MAX_PER_REQUEST}.
     */
    @Transactional
    public Result delete(Collection<UUID> requestedIds) {
        List<UUID> ids = requestedIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(requestedIds.stream().filter(java.util.Objects::nonNull).toList()));
        if (ids.isEmpty()) {
            throw new ApiException("Select at least one student to delete", HttpStatus.BAD_REQUEST);
        }
        if (ids.size() > MAX_PER_REQUEST) {
            throw new ApiException("You can delete at most " + MAX_PER_REQUEST + " students at a time",
                    HttpStatus.BAD_REQUEST);
        }

        // Lock the rows first so a concurrent attendance mark / invoice can't slip in between the check and
        // the delete (those inserts need a KEY SHARE lock on the student, which FOR UPDATE blocks).
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        Map<UUID, Row> found = new LinkedHashMap<>();
        jdbc.query(CANDIDATE_SELECT + """
                FROM students s
                LEFT JOIN sections sec ON sec.id = s.section_id
                LEFT JOIN classes c ON c.id = sec.class_id
                WHERE s.id IN (:ids)
                ORDER BY s.id
                FOR UPDATE OF s
                """, params, StudentBulkDeleteService::mapRow).forEach(row -> found.put(row.id(), row));

        Map<UUID, String> reasons = blockReasons(List.copyOf(found.keySet()));
        List<Deleted> deleted = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (UUID id : ids) {
            Row row = found.get(id);
            if (row == null) {
                skipped.add(new Skipped(id, null, null, "Student not found (already deleted?)"));
            } else if (reasons.containsKey(id)) {
                skipped.add(new Skipped(id, row.admissionNumber(), row.fullName(), reasons.get(id)));
            } else {
                deleted.add(new Deleted(id, row.admissionNumber(), row.fullName()));
            }
        }
        if (deleted.isEmpty()) {
            return new Result(deleted, skipped);
        }

        MapSqlParameterSource deleteParams = new MapSqlParameterSource("ids",
                deleted.stream().map(Deleted::id).toList());
        jdbc.update("UPDATE admission_enquiries SET converted_student_id = NULL WHERE converted_student_id IN (:ids)",
                deleteParams);
        jdbc.update("UPDATE admission_applications SET converted_student_id = NULL WHERE converted_student_id IN (:ids)",
                deleteParams);
        // student_documents, student_identifications and student_academic_history cascade.
        jdbc.update("DELETE FROM students WHERE id IN (:ids)", deleteParams);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Deleted student : deleted) {
                    removeFiles(student.id());
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("admissionNumber", student.admissionNumber());
                    details.put("fullName", student.fullName());
                    details.put("via", "BULK_DELETE");
                    auditService.log(AuditActions.STUDENT_DELETED, AuditActions.STUDENT, student.id(), details);
                }
            }
        });
        return new Result(deleted, skipped);
    }

    /** Reason text per student that has dependent history; students without any are absent. */
    private Map<UUID, String> blockReasons(List<UUID> ids) {
        Map<UUID, List<String>> found = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        BLOCKERS.forEach((table, label) -> jdbc.query(
                "SELECT DISTINCT student_id FROM " + table + " WHERE student_id IN (:ids)", params,
                rs -> {
                    found.computeIfAbsent(rs.getObject(1, UUID.class), key -> new ArrayList<>()).add(label);
                }));
        Map<UUID, String> reasons = new LinkedHashMap<>();
        found.forEach((id, labels) -> reasons.put(id, "Has " + String.join(", ", labels)
                + " -- disable this student instead"));
        return reasons;
    }

    private void removeFiles(UUID studentId) {
        Path dir = baseDir.resolve("students").resolve(studentId.toString()).normalize();
        if (!dir.startsWith(baseDir)) {
            return;
        }
        try {
            FileSystemUtils.deleteRecursively(dir);
        } catch (IOException ex) {
            log.warn("Could not remove files of deleted student {}: {}", studentId, ex.getMessage());
        }
    }

    private boolean exists(String sql, MapSqlParameterSource params) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, params, Boolean.class));
    }

    private record Row(UUID id, String admissionNumber, String fullName, String className, String sectionName,
                       LocalDate dateOfBirth, String gender, String category, String status, String mobile) {
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("admission_number"),
                rs.getString("full_name"), rs.getString("class_name"), rs.getString("section_name"),
                rs.getObject("date_of_birth", LocalDate.class), rs.getString("gender"),
                rs.getString("category"), rs.getString("status"), rs.getString("mobile"));
    }
}
