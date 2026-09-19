package com.smsapp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * School-level reporting against a real PostgreSQL instance. Covers the
 * plan's required checks (section 7c/7d): SCHOOL_ADMIN-only access
 * (TEACHER/STUDENT/PARENT -> 403), and correct aggregation math against
 * known seeded data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportIntegrationTest {

    private static final String ADMIN_A = "admin@report.example";
    private static final String TEACHER_A = "teacher@report.example";
    private static final String STUDENT_A = "student@report.example";
    private static final String PARENT_A = "parent@report.example";
    private static final String PASSWORD = "secret";
    private static final String DAY = "2026-03-02";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID classAId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID schoolId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        classAId = UUID.randomUUID();
        UUID examAId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, audit_log CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Report School')");

            UUID adminA = seedUser(st, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, TEACHER_A, "TEACHER");
            seedUser(st, STUDENT_A, "STUDENT");
            seedUser(st, PARENT_A, "PARENT");

            seedClass(st, schoolId, classAId, "Grade 5");
            seedSubject(st, schoolId, subjectId);

            // --- Attendance on DAY = 3 PRESENT + 1 ABSENT (-> 75%).
            for (int i = 0; i < 3; i++) {
                UUID s = seedStudent(st, schoolId, "A-present-" + i);
                seedAttendance(st, s, DAY, "PRESENT", adminA);
            }
            UUID absentStudent = seedStudent(st, schoolId, "A-absent");
            seedAttendance(st, absentStudent, DAY, "ABSENT", adminA);
            // A different day, so the range test can also confirm it is excluded.
            seedAttendance(st, absentStudent, "2026-02-01", "PRESENT", adminA);

            // --- Exams: class exam, marks 80 + 60 -> avg 70.
            seedExam(st, examAId, classAId, subjectId, "Mid-term A", "2026-04-01");
            UUID am1 = seedStudent(st, schoolId, "A-mark-1");
            UUID am2 = seedStudent(st, schoolId, "A-mark-2");
            seedMark(st, examAId, am1, "80.00");
            seedMark(st, examAId, am2, "60.00");

            // --- Fees: invoiced 10000, collected 5000, one overdue PENDING (3000).
            UUID pastFs = seedFeeStructure(st, schoolId, "Term 1 (overdue)", "5000.00", "2020-01-01");
            UUID futureFs = seedFeeStructure(st, schoolId, "Term 2", "2000.00", "2999-01-01");
            UUID fsStudent = seedStudent(st, schoolId, "A-fees");
            seedInvoice(st, fsStudent, pastFs, "5000.00", "PAID");
            seedInvoice(st, fsStudent, pastFs, "3000.00", "PENDING");  // overdue
            seedInvoice(st, fsStudent, futureFs, "2000.00", "PENDING"); // not overdue
        }
    }

    // --- seed helpers ------------------------------------------------

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '"
                + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('"
                + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('"
                + userId + "', '" + roleId + "')");
        return userId;
    }

    private static void seedClass(Statement st, UUID schoolId, UUID classId, String name)
            throws SQLException {
        st.execute("INSERT INTO classes (id, school_id, name) VALUES ('"
                + classId + "', '" + schoolId + "', '" + name + "')");
    }

    private static void seedSubject(Statement st, UUID schoolId, UUID subjectId) throws SQLException {
        st.execute("INSERT INTO subjects (id, school_id, name) VALUES ('"
                + subjectId + "', '" + schoolId + "', 'Mathematics')");
    }

    private static UUID seedStudent(Statement st, UUID schoolId, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute("INSERT INTO students (id, school_id, full_name, admission_number, status) VALUES ('"
                + id + "', '" + schoolId + "', '" + name + "', 'ADM-" + id + "', 'ACTIVE')");
        return id;
    }

    private static void seedAttendance(Statement st, UUID studentId, String date, String status,
                                       UUID markedBy) throws SQLException {
        st.execute("INSERT INTO attendance_records (id, student_id, date, status, marked_by) VALUES ('"
                + UUID.randomUUID() + "', '" + studentId + "', '" + date + "', '" + status
                + "', '" + markedBy + "')");
    }

    private static void seedExam(Statement st, UUID examId, UUID classId, UUID subjectId, String name,
                                 String date) throws SQLException {
        st.execute("INSERT INTO exams (id, class_id, subject_id, name, exam_date, max_marks) VALUES ('"
                + examId + "', '" + classId + "', '" + subjectId + "', '" + name + "', '"
                + date + "', 100)");
    }

    private static void seedMark(Statement st, UUID examId, UUID studentId, String marks)
            throws SQLException {
        st.execute("INSERT INTO exam_marks (id, exam_id, student_id, marks_obtained) VALUES ('"
                + UUID.randomUUID() + "', '" + examId + "', '" + studentId + "', " + marks + ")");
    }

    private static UUID seedFeeStructure(Statement st, UUID schoolId, String name, String amount,
                                         String dueDate) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute("INSERT INTO fee_structures (id, school_id, name, amount, due_date) VALUES ('"
                + id + "', '" + schoolId + "', '" + name + "', " + amount + ", '" + dueDate + "')");
        return id;
    }

    private static void seedInvoice(Statement st, UUID studentId, UUID feeStructureId, String amount,
                                    String status) throws SQLException {
        st.execute("INSERT INTO invoices (id, student_id, fee_structure_id, amount, status) VALUES ('"
                + UUID.randomUUID() + "', '" + studentId + "', '" + feeStructureId + "', "
                + amount + ", '" + status + "')");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private JsonNode getJson(String path, Cookie session) throws Exception {
        var result = mockMvc.perform(get(path).cookie(session)).andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // --- RBAC ------------------------------------------------------

    @Test
    void everyReportIsSchoolAdminOnly() throws Exception {
        String[] reports = {
                "/api/v1/reports/attendance-trend?from=2026-03-01&to=2026-03-31",
                "/api/v1/reports/academic-performance?classId=" + classAId,
                "/api/v1/reports/fee-collection",
        };
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            Cookie session = login(email);
            for (String report : reports) {
                mockMvc.perform(get(report).cookie(session)).andExpect(status().isForbidden());
            }
        }
    }

    // --- attendance trend ----------------------------------------

    @Test
    void attendanceTrendComputesDailyPercentage() throws Exception {
        JsonNode trend = getJson("/api/v1/reports/attendance-trend?from=2026-03-01&to=2026-03-31",
                login(ADMIN_A));

        // Only DAY falls in the range; 3 present + 1 absent -> 75%.
        org.assertj.core.api.Assertions.assertThat(trend).hasSize(1);
        JsonNode day = trend.get(0);
        org.assertj.core.api.Assertions.assertThat(day.get("date").asText()).isEqualTo(DAY);
        org.assertj.core.api.Assertions.assertThat(day.get("present").asInt()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(day.get("absent").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(day.get("total").asInt()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(day.get("attendancePercentage").asDouble()).isEqualTo(75.0);
    }

    @Test
    void attendanceTrendRespectsTheDateRange() throws Exception {
        // Widen to include the 2026-02-01 record too.
        JsonNode trend = getJson("/api/v1/reports/attendance-trend?from=2026-01-01&to=2026-12-31",
                login(ADMIN_A));
        org.assertj.core.api.Assertions.assertThat(trend).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(trend.get(0).get("date").asText()).isEqualTo("2026-02-01");
        org.assertj.core.api.Assertions.assertThat(trend.get(1).get("date").asText()).isEqualTo(DAY);
    }

    // --- academic performance -----------------------------------

    @Test
    void academicPerformanceAveragesMarksPerExam() throws Exception {
        JsonNode perf = getJson("/api/v1/reports/academic-performance?classId=" + classAId, login(ADMIN_A));

        org.assertj.core.api.Assertions.assertThat(perf).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(perf.get(0).get("examName").asText()).isEqualTo("Mid-term A");
        org.assertj.core.api.Assertions.assertThat(perf.get(0).get("averageMarks").asDouble()).isEqualTo(70.0);
        org.assertj.core.api.Assertions.assertThat(perf.get(0).get("studentsGraded").asInt()).isEqualTo(2);

        // Asking for a nonexistent class id gets nothing.
        JsonNode noClass = getJson("/api/v1/reports/academic-performance?classId=" + UUID.randomUUID(),
                login(ADMIN_A));
        org.assertj.core.api.Assertions.assertThat(noClass).isEmpty();
    }

    // --- fee collection ----------------------------------------

    @Test
    void feeCollectionSummariesAndDefaulterList() throws Exception {
        JsonNode report = getJson("/api/v1/reports/fee-collection", login(ADMIN_A));

        org.assertj.core.api.Assertions.assertThat(report.get("totalInvoiced").asDouble()).isEqualTo(10000.0);
        org.assertj.core.api.Assertions.assertThat(report.get("totalCollected").asDouble()).isEqualTo(5000.0);
        org.assertj.core.api.Assertions.assertThat(report.get("outstanding").asDouble()).isEqualTo(5000.0);

        JsonNode overdue = report.get("overdueInvoices");
        org.assertj.core.api.Assertions.assertThat(overdue).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(overdue.get(0).get("amount").asDouble()).isEqualTo(3000.0);
        org.assertj.core.api.Assertions.assertThat(overdue.get(0).get("feeStructureName").asText())
                .isEqualTo("Term 1 (overdue)");
    }

    @Test
    void attendanceTrendRejectsAMissingDateParam() throws Exception {
        mockMvc.perform(get("/api/v1/reports/attendance-trend?from=2026-03-01").cookie(login(ADMIN_A)))
                .andExpect(status().isBadRequest());
    }
}
