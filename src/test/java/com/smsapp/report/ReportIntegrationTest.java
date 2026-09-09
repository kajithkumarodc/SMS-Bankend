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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * School-level reporting against a real PostgreSQL instance with RLS. Covers the
 * plan's required checks (section 7c/7d): tenant isolation on every report (tenant
 * B's rows never appear in tenant A's report), SCHOOL_ADMIN-only access
 * (TEACHER/STUDENT/PARENT -> 403), and correct aggregation math against known
 * seeded data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportIntegrationTest {

    private static final String SCHOOL_A = "report-a";
    private static final String SCHOOL_B = "report-b";
    private static final String ADMIN_A = "admin@report-a.example";
    private static final String TEACHER_A = "teacher@report-a.example";
    private static final String STUDENT_A = "student@report-a.example";
    private static final String PARENT_A = "parent@report-a.example";
    private static final String ADMIN_B = "admin@report-b.example";
    private static final String PASSWORD = "secret";
    private static final String DAY = "2026-03-02";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID classAId;
    private UUID classBId;
    private UUID examAId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID schoolAId = UUID.randomUUID();
        UUID schoolBId = UUID.randomUUID();
        UUID subjectAId = UUID.randomUUID();
        UUID subjectBId = UUID.randomUUID();
        classAId = UUID.randomUUID();
        classBId = UUID.randomUUID();
        examAId = UUID.randomUUID();
        UUID examBId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, audit_log CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolAId);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolBId);

            UUID adminA = seedUser(st, tenantA, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, tenantA, TEACHER_A, "TEACHER");
            seedUser(st, tenantA, STUDENT_A, "STUDENT");
            seedUser(st, tenantA, PARENT_A, "PARENT");
            UUID adminB = seedUser(st, tenantB, ADMIN_B, "SCHOOL_ADMIN");

            seedClass(st, tenantA, schoolAId, classAId, "Grade 5");
            seedClass(st, tenantB, schoolBId, classBId, "Grade 5");
            seedSubject(st, tenantA, schoolAId, subjectAId);
            seedSubject(st, tenantB, schoolBId, subjectBId);

            // --- Attendance: tenant A on DAY = 3 PRESENT + 1 ABSENT (-> 75%). Tenant B: 1 PRESENT same day.
            for (int i = 0; i < 3; i++) {
                UUID s = seedStudent(st, tenantA, schoolAId, "A-present-" + i);
                seedAttendance(st, tenantA, s, DAY, "PRESENT", adminA);
            }
            UUID absentStudent = seedStudent(st, tenantA, schoolAId, "A-absent");
            seedAttendance(st, tenantA, absentStudent, DAY, "ABSENT", adminA);
            // A different day, so the range test can also confirm it is excluded.
            seedAttendance(st, tenantA, absentStudent, "2026-02-01", "PRESENT", adminA);

            UUID bStudent = seedStudent(st, tenantB, schoolBId, "B-present");
            seedAttendance(st, tenantB, bStudent, DAY, "PRESENT", adminB);

            // --- Exams: tenant A class exam, marks 80 + 60 -> avg 70. Tenant B has its own.
            seedExam(st, tenantA, examAId, classAId, subjectAId, "Mid-term A", "2026-04-01");
            UUID am1 = seedStudent(st, tenantA, schoolAId, "A-mark-1");
            UUID am2 = seedStudent(st, tenantA, schoolAId, "A-mark-2");
            seedMark(st, tenantA, examAId, am1, "80.00");
            seedMark(st, tenantA, examAId, am2, "60.00");

            seedExam(st, tenantB, examBId, classBId, subjectBId, "Mid-term B", "2026-04-01");
            UUID bm = seedStudent(st, tenantB, schoolBId, "B-mark");
            seedMark(st, tenantB, examBId, bm, "10.00");

            // --- Fees: tenant A invoiced 10000, collected 5000, one overdue PENDING (3000).
            UUID pastFs = seedFeeStructure(st, tenantA, schoolAId, "Term 1 (overdue)", "5000.00", "2020-01-01");
            UUID futureFs = seedFeeStructure(st, tenantA, schoolAId, "Term 2", "2000.00", "2999-01-01");
            UUID fsStudent = seedStudent(st, tenantA, schoolAId, "A-fees");
            seedInvoice(st, tenantA, fsStudent, pastFs, "5000.00", "PAID");
            seedInvoice(st, tenantA, fsStudent, pastFs, "3000.00", "PENDING");  // overdue
            seedInvoice(st, tenantA, fsStudent, futureFs, "2000.00", "PENDING"); // not overdue

            UUID bFs = seedFeeStructure(st, tenantB, schoolBId, "B Term", "9999.00", "2020-01-01");
            UUID bFsStudent = seedStudent(st, tenantB, schoolBId, "B-fees");
            seedInvoice(st, tenantB, bFsStudent, bFs, "9999.00", "PAID");
            seedInvoice(st, tenantB, bFsStudent, bFs, "7777.00", "PENDING");
        }
    }

    // --- seed helpers ------------------------------------------------

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, UUID schoolId)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
    }

    private UUID seedUser(Statement st, UUID tenantId, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '"
                + email + "')");
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
        return userId;
    }

    private static void seedClass(Statement st, UUID tenantId, UUID schoolId, UUID classId, String name)
            throws SQLException {
        st.execute("INSERT INTO classes (id, tenant_id, school_id, name) VALUES ('"
                + classId + "', '" + tenantId + "', '" + schoolId + "', '" + name + "')");
    }

    private static void seedSubject(Statement st, UUID tenantId, UUID schoolId, UUID subjectId) throws SQLException {
        st.execute("INSERT INTO subjects (id, tenant_id, school_id, name) VALUES ('"
                + subjectId + "', '" + tenantId + "', '" + schoolId + "', 'Mathematics')");
    }

    private static UUID seedStudent(Statement st, UUID tenantId, UUID schoolId, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status) VALUES ('"
                + id + "', '" + tenantId + "', '" + schoolId + "', '" + name + "', 'ADM-" + id + "', 'ACTIVE')");
        return id;
    }

    private static void seedAttendance(Statement st, UUID tenantId, UUID studentId, String date, String status,
                                       UUID markedBy) throws SQLException {
        st.execute("INSERT INTO attendance_records (id, tenant_id, student_id, date, status, marked_by) VALUES ('"
                + UUID.randomUUID() + "', '" + tenantId + "', '" + studentId + "', '" + date + "', '" + status
                + "', '" + markedBy + "')");
    }

    private static void seedExam(Statement st, UUID tenantId, UUID examId, UUID classId, UUID subjectId, String name,
                                 String date) throws SQLException {
        st.execute("INSERT INTO exams (id, tenant_id, class_id, subject_id, name, exam_date, max_marks) VALUES ('"
                + examId + "', '" + tenantId + "', '" + classId + "', '" + subjectId + "', '" + name + "', '"
                + date + "', 100)");
    }

    private static void seedMark(Statement st, UUID tenantId, UUID examId, UUID studentId, String marks)
            throws SQLException {
        st.execute("INSERT INTO exam_marks (id, tenant_id, exam_id, student_id, marks_obtained) VALUES ('"
                + UUID.randomUUID() + "', '" + tenantId + "', '" + examId + "', '" + studentId + "', " + marks + ")");
    }

    private static UUID seedFeeStructure(Statement st, UUID tenantId, UUID schoolId, String name, String amount,
                                         String dueDate) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute("INSERT INTO fee_structures (id, tenant_id, school_id, name, amount, due_date) VALUES ('"
                + id + "', '" + tenantId + "', '" + schoolId + "', '" + name + "', " + amount + ", '" + dueDate + "')");
        return id;
    }

    private static void seedInvoice(Statement st, UUID tenantId, UUID studentId, UUID feeStructureId, String amount,
                                    String status) throws SQLException {
        st.execute("INSERT INTO invoices (id, tenant_id, student_id, fee_structure_id, amount, status) VALUES ('"
                + UUID.randomUUID() + "', '" + tenantId + "', '" + studentId + "', '" + feeStructureId + "', "
                + amount + ", '" + status + "')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
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
            Cookie session = login(SCHOOL_A, email);
            for (String report : reports) {
                mockMvc.perform(get(report).cookie(session)).andExpect(status().isForbidden());
            }
        }
    }

    // --- attendance trend ----------------------------------------

    @Test
    void attendanceTrendComputesDailyPercentageAndIsTenantScoped() throws Exception {
        JsonNode trend = getJson("/api/v1/reports/attendance-trend?from=2026-03-01&to=2026-03-31",
                login(SCHOOL_A, ADMIN_A));

        // Only DAY falls in the range; 3 present + 1 absent -> 75%.
        org.assertj.core.api.Assertions.assertThat(trend).hasSize(1);
        JsonNode day = trend.get(0);
        org.assertj.core.api.Assertions.assertThat(day.get("date").asText()).isEqualTo(DAY);
        org.assertj.core.api.Assertions.assertThat(day.get("present").asInt()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(day.get("absent").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(day.get("total").asInt()).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(day.get("attendancePercentage").asDouble()).isEqualTo(75.0);

        // Tenant B admin sees only tenant B's single PRESENT that day -> 100%, not tenant A's 4 records.
        JsonNode bTrend = getJson("/api/v1/reports/attendance-trend?from=2026-03-01&to=2026-03-31",
                login(SCHOOL_B, ADMIN_B));
        org.assertj.core.api.Assertions.assertThat(bTrend).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(bTrend.get(0).get("total").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(bTrend.get(0).get("attendancePercentage").asDouble()).isEqualTo(100.0);
    }

    @Test
    void attendanceTrendRespectsTheDateRange() throws Exception {
        // Widen to include the 2026-02-01 record too.
        JsonNode trend = getJson("/api/v1/reports/attendance-trend?from=2026-01-01&to=2026-12-31",
                login(SCHOOL_A, ADMIN_A));
        org.assertj.core.api.Assertions.assertThat(trend).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(trend.get(0).get("date").asText()).isEqualTo("2026-02-01");
        org.assertj.core.api.Assertions.assertThat(trend.get(1).get("date").asText()).isEqualTo(DAY);
    }

    // --- academic performance -----------------------------------

    @Test
    void academicPerformanceAveragesMarksPerExamAndIsTenantScoped() throws Exception {
        JsonNode perf = getJson("/api/v1/reports/academic-performance?classId=" + classAId, login(SCHOOL_A, ADMIN_A));

        org.assertj.core.api.Assertions.assertThat(perf).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(perf.get(0).get("examName").asText()).isEqualTo("Mid-term A");
        org.assertj.core.api.Assertions.assertThat(perf.get(0).get("averageMarks").asDouble()).isEqualTo(70.0);
        org.assertj.core.api.Assertions.assertThat(perf.get(0).get("studentsGraded").asInt()).isEqualTo(2);

        // Tenant A asking for tenant B's class id gets nothing -- B's marks are filtered out by tenant.
        JsonNode crossTenant = getJson("/api/v1/reports/academic-performance?classId=" + classBId,
                login(SCHOOL_A, ADMIN_A));
        org.assertj.core.api.Assertions.assertThat(crossTenant).isEmpty();
    }

    // --- fee collection ----------------------------------------

    @Test
    void feeCollectionSummariesAndDefaulterListAreTenantScoped() throws Exception {
        JsonNode report = getJson("/api/v1/reports/fee-collection", login(SCHOOL_A, ADMIN_A));

        org.assertj.core.api.Assertions.assertThat(report.get("totalInvoiced").asDouble()).isEqualTo(10000.0);
        org.assertj.core.api.Assertions.assertThat(report.get("totalCollected").asDouble()).isEqualTo(5000.0);
        org.assertj.core.api.Assertions.assertThat(report.get("outstanding").asDouble()).isEqualTo(5000.0);

        JsonNode overdue = report.get("overdueInvoices");
        org.assertj.core.api.Assertions.assertThat(overdue).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(overdue.get(0).get("amount").asDouble()).isEqualTo(3000.0);
        org.assertj.core.api.Assertions.assertThat(overdue.get(0).get("feeStructureName").asText())
                .isEqualTo("Term 1 (overdue)");

        // Tenant B's totals are entirely its own.
        JsonNode bReport = getJson("/api/v1/reports/fee-collection", login(SCHOOL_B, ADMIN_B));
        org.assertj.core.api.Assertions.assertThat(bReport.get("totalInvoiced").asDouble()).isEqualTo(17776.0);
        org.assertj.core.api.Assertions.assertThat(bReport.get("totalCollected").asDouble()).isEqualTo(9999.0);
        org.assertj.core.api.Assertions.assertThat(bReport.get("overdueInvoices")).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(bReport.get("overdueInvoices").get(0).get("amount").asDouble())
                .isEqualTo(7777.0);
    }

    @Test
    void attendanceTrendRejectsAMissingDateParam() throws Exception {
        mockMvc.perform(get("/api/v1/reports/attendance-trend?from=2026-03-01").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isBadRequest());
    }
}
