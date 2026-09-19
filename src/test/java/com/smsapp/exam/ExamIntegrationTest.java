package com.smsapp.exam;

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
 * Exams + marks against a real PostgreSQL instance: TEACHER may create exams
 * and record marks, marks over the max are rejected 400, re-recording is an
 * upsert, and a nonexistent class / subject / student / exam reference
 * returns 404 (plan section 2 / 7c-d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExamIntegrationTest {

    private static final String ADMIN_A = "admin@tenant-a.example";
    private static final String TEACHER_A = "teacher@tenant-a.example";
    private static final String STUDENT_A = "student@tenant-a.example";
    private static final String PARENT_A = "parent@tenant-a.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID classA;
    private UUID subjectA;
    private UUID studentA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID schoolA = UUID.randomUUID();
        classA = UUID.randomUUID();
        subjectA = UUID.randomUUID();
        studentA = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Tenant A School')");
            st.execute("INSERT INTO classes (id, school_id, name) VALUES ('"
                    + classA + "', '" + schoolA + "', 'Grade 5')");
            st.execute("INSERT INTO subjects (id, school_id, name) VALUES ('"
                    + subjectA + "', '" + schoolA + "', 'Mathematics')");
            st.execute("INSERT INTO students (id, school_id, full_name, admission_number, status) VALUES ('"
                    + studentA + "', '" + schoolA + "', 'Student A', 'ADM-A', 'ACTIVE')");

            seedUser(st, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, TEACHER_A, "TEACHER");
            seedUser(st, STUDENT_A, "STUDENT");
            seedUser(st, PARENT_A, "PARENT");
        }
    }

    private void seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String examBody(String name, String maxMarks) {
        return "{\"classId\":\"" + classA + "\",\"subjectId\":\"" + subjectA + "\",\"name\":\"" + name
                + "\",\"examDate\":\"2026-10-15\",\"maxMarks\":" + maxMarks + "}";
    }

    private UUID createExamA(Cookie cookie, String name, String maxMarks) throws Exception {
        var result = mockMvc.perform(post("/api/v1/exams").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(examBody(name, maxMarks)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Exam creation ----------------------------------------------

    @Test
    void teacherCanCreateAnExam() throws Exception {
        mockMvc.perform(post("/api/v1/exams").cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(examBody("Mid-term 2026", "100")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Mid-term 2026"))
                .andExpect(jsonPath("$.classId").value(classA.toString()))
                .andExpect(jsonPath("$.maxMarks").value(100));
    }

    @Test
    void schoolAdminCanCreateAnExam() throws Exception {
        mockMvc.perform(post("/api/v1/exams").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content(examBody("Unit Test 1", "25")))
                .andExpect(status().isCreated());
    }

    @Test
    void cannotCreateExamWithANonexistentClass() throws Exception {
        mockMvc.perform(post("/api/v1/exams").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"" + UUID.randomUUID() + "\",\"subjectId\":\"" + subjectA
                                + "\",\"name\":\"X\",\"examDate\":\"2026-10-15\",\"maxMarks\":100}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotCreateExamWithANonexistentSubject() throws Exception {
        mockMvc.perform(post("/api/v1/exams").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"" + classA + "\",\"subjectId\":\"" + UUID.randomUUID()
                                + "\",\"name\":\"X\",\"examDate\":\"2026-10-15\",\"maxMarks\":100}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listExamsForClass() throws Exception {
        Cookie admin = login(ADMIN_A);
        createExamA(admin, "Mid-term 2026", "100");

        mockMvc.perform(get("/api/v1/exams").param("classId", classA.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Mid-term 2026"));
    }

    @Test
    void listExamsForANonexistentClassReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/exams").param("classId", UUID.randomUUID().toString()).cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Role gating: staff-only read endpoints -------------------

    @Test
    void studentsAndParentsCannotReachStaffExamReadEndpoints() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID exam = createExamA(admin, "Mid-term 2026", "100");

        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            Cookie session = login(email);
            // Exam list for a class -- staff only; portal uses /me/... results.
            mockMvc.perform(get("/api/v1/exams").param("classId", classA.toString()).cookie(session))
                    .andExpect(status().isForbidden());
            // Whole gradebook (every student's marks) -- staff only.
            mockMvc.perform(get("/api/v1/exams/" + exam + "/marks").cookie(session))
                    .andExpect(status().isForbidden());
            // Raw per-student results -- staff only, even for the caller's own id.
            mockMvc.perform(get("/api/v1/exams/student/" + studentA).cookie(session))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Recording marks ------------------------------------------

    @Test
    void teacherCanRecordMarksAndReRecordingIsAnUpsert() throws Exception {
        Cookie teacher = login(TEACHER_A);
        UUID exam = createExamA(teacher, "Mid-term 2026", "100");

        mockMvc.perform(post("/api/v1/exams/" + exam + "/marks").cookie(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentA + "\",\"marksObtained\":80}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.marksObtained").value(80));

        // Correction: not a 409, an update (200).
        mockMvc.perform(post("/api/v1/exams/" + exam + "/marks").cookie(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentA + "\",\"marksObtained\":85.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marksObtained").value(85.5));

        mockMvc.perform(get("/api/v1/exams/" + exam + "/marks").cookie(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].marksObtained").value(85.5));
    }

    @Test
    void marksExceedingMaxAreRejectedWith400() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID exam = createExamA(admin, "Unit Test 1", "25");

        mockMvc.perform(post("/api/v1/exams/" + exam + "/marks").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentA + "\",\"marksObtained\":26}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotRecordMarksForANonexistentStudent() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID exam = createExamA(admin, "Mid-term 2026", "100");

        mockMvc.perform(post("/api/v1/exams/" + exam + "/marks").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + UUID.randomUUID() + "\",\"marksObtained\":50}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotRecordMarksForANonexistentExam() throws Exception {
        mockMvc.perform(post("/api/v1/exams/" + UUID.randomUUID() + "/marks").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentA + "\",\"marksObtained\":50}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void gradebookForANonexistentExamReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/exams/" + UUID.randomUUID() + "/marks").cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Student results ----------------------------------------

    @Test
    void studentResultsSpanAllExams() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID mid = createExamA(admin, "Mid-term 2026", "100");
        UUID unit = createExamA(admin, "Unit Test 1", "25");
        mockMvc.perform(post("/api/v1/exams/" + mid + "/marks").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentA + "\",\"marksObtained\":78}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/exams/" + unit + "/marks").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentA + "\",\"marksObtained\":22}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/exams/student/" + studentA).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void studentResultsForANonexistentStudentReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/exams/student/" + UUID.randomUUID()).cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }
}
