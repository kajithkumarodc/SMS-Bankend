package com.smsapp.portal;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Portal self-service against a real PostgreSQL instance. Proves the
 * OWNERSHIP isolation layer: a student can only reach their own data, a
 * parent only their own children; and an unlinked account gets a clean 404,
 * not an error.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalIntegrationTest {

    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String SU1 = "SU1@portal.example";  // STUDENT linked to S1
    private static final String SU2 = "SU2@portal.example";  // STUDENT linked to S2
    private static final String GU1 = "GU1@portal.example";  // PARENT of S1 + sibling
    private static final String GU2 = "GU2@portal.example";  // PARENT of S2
    private static final String LONELY_STUDENT = "nolink-student@portal.example";
    private static final String LONELY_PARENT = "nokids-parent@portal.example";

    private UUID s1Id;
    private UUID s2Id;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID school = UUID.randomUUID();
        s1Id = UUID.randomUUID();
        UUID s1bId = UUID.randomUUID();
        s2Id = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes, subjects, exams, exam_marks, audit_log CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + school + "', 'Portal School')");

            UUID studentRole = seedRole(st, "STUDENT");
            UUID parentRole = seedRole(st, "PARENT");

            UUID su1Id = seedUser(st, SU1, studentRole);
            UUID su2Id = seedUser(st, SU2, studentRole);
            UUID gu1Id = seedUser(st, GU1, parentRole);
            UUID gu2Id = seedUser(st, GU2, parentRole);
            seedUser(st, LONELY_STUDENT, studentRole);
            seedUser(st, LONELY_PARENT, parentRole);

            // S1 (Anaya) -> SU1 / GU1 ; sibling (Arjun) -> GU1 ; S2 (Bala) -> SU2 / GU2
            seedStudent(st, school, s1Id, "Anaya", su1Id, gu1Id);
            seedStudent(st, school, s1bId, "Arjun", null, gu1Id);
            seedStudent(st, school, s2Id, "Bala", su2Id, gu2Id);

            // Attendance: S1 has 2 records, S2 has 1.
            seedAttendance(st, s1Id, "2026-08-24", "PRESENT", su1Id);
            seedAttendance(st, s1Id, "2026-08-25", "ABSENT", su1Id);
            seedAttendance(st, s2Id, "2026-08-24", "PRESENT", su2Id);

            // Exams: one class + subject, two exams. S1 has marks in both, S2 in one.
            UUID classA = UUID.randomUUID();
            UUID subjectA = UUID.randomUUID();
            seedClass(st, school, classA, "Grade 5");
            seedSubject(st, school, subjectA, "Mathematics");
            UUID examA1 = UUID.randomUUID();
            UUID examA2 = UUID.randomUUID();
            seedExam(st, examA1, classA, subjectA, "Unit Test 1", "2026-08-10");
            seedExam(st, examA2, classA, subjectA, "Unit Test 2", "2026-08-20");
            seedMark(st, examA1, s1Id, "88");
            seedMark(st, examA2, s1Id, "40");
            seedMark(st, examA1, s2Id, "55");
        }
    }

    private static UUID seedRole(Statement st, String role) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '" + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return userId;
    }

    private static void seedStudent(Statement st, UUID schoolId, UUID studentId, String name,
                                    UUID studentUserId, UUID guardianUserId) throws SQLException {
        st.execute("INSERT INTO students (id, school_id, full_name, admission_number, status, "
                + "student_user_id, guardian_user_id) VALUES ('" + studentId + "', '" + schoolId
                + "', '" + name + "', 'ADM-" + name + "', 'ACTIVE', "
                + (studentUserId == null ? "NULL" : "'" + studentUserId + "'") + ", "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ")");
    }

    private static void seedAttendance(Statement st, UUID studentId, String date, String attStatus,
                                       UUID markedBy) throws SQLException {
        st.execute("INSERT INTO attendance_records (id, student_id, date, status, marked_by) VALUES ('"
                + UUID.randomUUID() + "', '" + studentId + "', '" + date + "', '" + attStatus
                + "', '" + markedBy + "')");
    }

    private static void seedClass(Statement st, UUID schoolId, UUID classId, String name)
            throws SQLException {
        st.execute("INSERT INTO classes (id, school_id, name) VALUES ('"
                + classId + "', '" + schoolId + "', '" + name + "')");
    }

    private static void seedSubject(Statement st, UUID schoolId, UUID subjectId, String name)
            throws SQLException {
        st.execute("INSERT INTO subjects (id, school_id, name) VALUES ('"
                + subjectId + "', '" + schoolId + "', '" + name + "')");
    }

    private static void seedExam(Statement st, UUID examId, UUID classId, UUID subjectId, String name,
                                 String examDate) throws SQLException {
        st.execute("INSERT INTO exams (id, class_id, subject_id, name, exam_date, max_marks) VALUES ('"
                + examId + "', '" + classId + "', '" + subjectId + "', '" + name + "', '"
                + examDate + "', 100)");
    }

    private static void seedMark(Statement st, UUID examId, UUID studentId, String marks)
            throws SQLException {
        st.execute("INSERT INTO exam_marks (id, exam_id, student_id, marks_obtained) VALUES ('"
                + UUID.randomUUID() + "', '" + examId + "', '" + studentId + "', " + marks + ")");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private JsonNode json(String path, Cookie session) throws Exception {
        var result = mockMvc.perform(get(path).cookie(session)).andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // --- Student self-service ----------------------------------------

    @Test
    void studentSeesOnlyTheirOwnRecord() throws Exception {
        mockMvc.perform(get("/api/v1/me/student").cookie(login(SU1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s1Id.toString()))
                .andExpect(jsonPath("$.fullName").value("Anaya"))
                .andExpect(jsonPath("$.admissionNumber").value("ADM-Anaya"));
    }

    @Test
    void studentAttendanceIsScopedToTheirOwnStudentId() throws Exception {
        // SU1 -> S1's two records; SU2 -> S2's single record.
        JsonNode su1History = json("/api/v1/me/student/attendance", login(SU1));
        assertContentSize(su1History, 2);

        JsonNode su2History = json("/api/v1/me/student/attendance", login(SU2));
        assertContentSize(su2History, 1);
        assertThat(su2History.get("content").get(0).get("status").asText())
                .isEqualTo("PRESENT");
    }

    @Test
    void studentWithNoLinkedRecordGetsCleanNotFound() throws Exception {
        Cookie session = login(LONELY_STUDENT);
        mockMvc.perform(get("/api/v1/me/student").cookie(session)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/me/student/attendance").cookie(session)).andExpect(status().isNotFound());
    }

    // --- Parent self-service ----------------------------------------

    @Test
    void parentSeesAllOfTheirOwnChildrenAndNoOthers() throws Exception {
        JsonNode children = json("/api/v1/me/children", login(GU1));
        assertThat(children).hasSize(2);
        assertThat(children).extracting(n -> n.get("fullName").asText())
                .containsExactly("Anaya", "Arjun"); // ordered by full name
    }

    @Test
    void parentWithNoChildrenGetsAnEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/me/children").cookie(login(LONELY_PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void parentCanReadTheirOwnChildsAttendance() throws Exception {
        JsonNode history = json("/api/v1/me/children/" + s1Id + "/attendance", login(GU1));
        assertContentSize(history, 2);
    }

    @Test
    void parentCannotReadANonChildsAttendance() throws Exception {
        // S2 (Bala) belongs to GU2, not GU1.
        mockMvc.perform(get("/api/v1/me/children/" + s2Id + "/attendance").cookie(login(GU1)))
                .andExpect(status().isNotFound());
    }

    // --- Role gating ----------------------------------------------

    @Test
    void studentEndpointsRejectParentsAndViceVersa() throws Exception {
        mockMvc.perform(get("/api/v1/me/children").cookie(login(SU1)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/student").cookie(login(GU1)))
                .andExpect(status().isForbidden());
    }

    // --- Exam results: ownership -----------------------------------

    @Test
    void studentSeesOnlyTheirOwnExamResults() throws Exception {
        JsonNode su1Results = json("/api/v1/me/student/results", login(SU1));
        assertThat(su1Results).hasSize(2); // marks in both exams

        JsonNode su2Results = json("/api/v1/me/student/results", login(SU2));
        assertThat(su2Results).hasSize(1);
        assertThat(su2Results.get(0).get("marksObtained").asDouble()).isEqualTo(55.0);
    }

    @Test
    void studentCannotUseTheStaffResultsEndpointEvenForTheirOwnId() throws Exception {
        // SU1 IS S1 -- still forbidden: the staff endpoint is SCHOOL_ADMIN/TEACHER only.
        mockMvc.perform(get("/api/v1/exams/student/" + s1Id).cookie(login(SU1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void parentCanReadTheirOwnChildsExamResults() throws Exception {
        JsonNode results = json("/api/v1/me/children/" + s1Id + "/results", login(GU1));
        assertThat(results).hasSize(2);
    }

    @Test
    void parentCannotReadANonChildsExamResults() throws Exception {
        // S2 (Bala) belongs to GU2, not GU1.
        mockMvc.perform(get("/api/v1/me/children/" + s2Id + "/results").cookie(login(GU1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void parentCannotUseTheStaffResultsEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/exams/student/" + s1Id).cookie(login(GU1)))
                .andExpect(status().isForbidden());
    }

    // --- Dashboard integration -----------------------------------

    @Test
    void studentDashboardShowsOwnInfoAndAttendanceSummary() throws Exception {
        JsonNode summary = json("/api/v1/dashboard/summary", login(SU1));
        assertThat(summary.get("placeholder").asBoolean()).isFalse();
        assertThat(summary.get("student").get("fullName").asText()).isEqualTo("Anaya");
        assertThat(summary.get("attendance").get("total").asLong()).isEqualTo(2L);
        assertThat(summary.get("attendance").get("present").asLong()).isEqualTo(1L);
        assertThat(summary.get("attendance").get("absent").asLong()).isEqualTo(1L);
    }

    @Test
    void parentDashboardShowsChildren() throws Exception {
        JsonNode summary = json("/api/v1/dashboard/summary", login(GU1));
        assertThat(summary.get("placeholder").asBoolean()).isFalse();
        assertThat(summary.get("children")).hasSize(2);
    }

    private static void assertContentSize(JsonNode pagedModel, int expected) {
        assertThat(pagedModel.get("content")).hasSize(expected);
    }
}
