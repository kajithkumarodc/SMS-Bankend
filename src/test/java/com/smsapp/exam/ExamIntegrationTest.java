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
 * Exams + marks against a real PostgreSQL instance with RLS: tenant isolation on
 * exams and marks, TEACHER may create exams and record marks, marks over the max
 * are rejected 400, re-recording is an upsert, and cross-tenant class / subject /
 * student / exam references return 404 (plan section 2 / 7c-d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExamIntegrationTest {

    private static final String SCHOOL_A = "exam-school-a";
    private static final String SCHOOL_B = "exam-school-b";
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
    private UUID classB;
    private UUID subjectB;
    private UUID studentB;
    private UUID examB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID schoolA = UUID.randomUUID();
        UUID schoolB = UUID.randomUUID();
        classA = UUID.randomUUID();
        subjectA = UUID.randomUUID();
        studentA = UUID.randomUUID();
        classB = UUID.randomUUID();
        subjectB = UUID.randomUUID();
        studentB = UUID.randomUUID();
        examB = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes CASCADE");

            seedAcademics(st, tenantA, "Tenant A", SCHOOL_A, schoolA, classA, subjectA, studentA);
            seedAcademics(st, tenantB, "Tenant B", SCHOOL_B, schoolB, classB, subjectB, studentB);

            seedUser(st, tenantA, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, tenantA, TEACHER_A, "TEACHER");
            seedUser(st, tenantA, STUDENT_A, "STUDENT");
            seedUser(st, tenantA, PARENT_A, "PARENT");
            seedUser(st, tenantB, "admin@tenant-b.example", "SCHOOL_ADMIN");

            // Tenant B already owns an exam -- tenant A must never see or use it.
            st.execute("INSERT INTO exams (id, tenant_id, class_id, subject_id, name, exam_date, max_marks) VALUES ('"
                    + examB + "', '" + tenantB + "', '" + classB + "', '" + subjectB
                    + "', 'B Term', '2026-09-01', 100)");
        }
    }

    private static void seedAcademics(Statement st, UUID tenantId, String name, String identifier, UUID schoolId,
                                      UUID classId, UUID subjectId, UUID studentId) throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
        st.execute("INSERT INTO classes (id, tenant_id, school_id, name) VALUES ('"
                + classId + "', '" + tenantId + "', '" + schoolId + "', 'Grade 5')");
        st.execute("INSERT INTO subjects (id, tenant_id, school_id, name) VALUES ('"
                + subjectId + "', '" + tenantId + "', '" + schoolId + "', 'Mathematics')");
        st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status) VALUES ('"
                + studentId + "', '" + tenantId + "', '" + schoolId + "', 'Student " + identifier + "', 'ADM-"
                + identifier + "', 'ACTIVE')");
    }

    private void seedUser(Statement st, UUID tenantId, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '"
                + email + "')");
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"secret\"}"))
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
        mockMvc.perform(post("/api/v1/exams").cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content(examBody("Mid-term 2026", "100")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Mid-term 2026"))
                .andExpect(jsonPath("$.classId").value(classA.toString()))
                .andExpect(jsonPath("$.maxMarks").value(100));
    }

    @Test
    void schoolAdminCanCreateAnExam() throws Exception {
        mockMvc.perform(post("/api/v1/exams").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content(examBody("Unit Test 1", "25")))
                .andExpect(status().isCreated());
    }

    @Test
    void cannotCreateExamWithAnotherTenantsClass() throws Exception {
        mockMvc.perform(post("/api/v1/exams").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"" + classB + "\",\"subjectId\":\"" + subjectA
                                + "\",\"name\":\"X\",\"examDate\":\"2026-10-15\",\"maxMarks\":100}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotCreateExamWithAnotherTenantsSubject() throws Exception {
        mockMvc.perform(post("/api/v1/exams").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"classId\":\"" + classA + "\",\"subjectId\":\"" + subjectB
                                + "\",\"name\":\"X\",\"examDate\":\"2026-10-15\",\"maxMarks\":100}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listExamsForClassIsTenantScoped() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        createExamA(admin, "Mid-term 2026", "100");

        mockMvc.perform(get("/api/v1/exams").param("classId", classA.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Mid-term 2026"));

        // Tenant A cannot list a tenant-B class's exams.
        mockMvc.perform(get("/api/v1/exams").param("classId", classB.toString()).cookie(admin))
                .andExpect(status().isNotFound());
    }

    // --- Role gating: staff-only read endpoints -------------------

    @Test
    void studentsAndParentsCannotReachStaffExamReadEndpoints() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID exam = createExamA(admin, "Mid-term 2026", "100");

        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            Cookie session = login(SCHOOL_A, email);
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
        Cookie teacher = login(SCHOOL_A, TEACHER_A);
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
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID exam = createExamA(admin, "Unit Test 1", "25");

        mockMvc.perform(post("/api/v1/exams/" + exam + "/marks").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentA + "\",\"marksObtained\":26}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotRecordMarksForAnotherTenantsStudent() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID exam = createExamA(admin, "Mid-term 2026", "100");

        mockMvc.perform(post("/api/v1/exams/" + exam + "/marks").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentB + "\",\"marksObtained\":50}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotRecordMarksForAnotherTenantsExam() throws Exception {
        mockMvc.perform(post("/api/v1/exams/" + examB + "/marks").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentA + "\",\"marksObtained\":50}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void gradebookForAnotherTenantsExamReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/exams/" + examB + "/marks").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Student results ----------------------------------------

    @Test
    void studentResultsSpanAllExamsAndAreTenantScoped() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
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

        // Tenant A cannot read a tenant-B student's results.
        mockMvc.perform(get("/api/v1/exams/student/" + studentB).cookie(admin))
                .andExpect(status().isNotFound());
    }
}
