package com.smsapp.student;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves, against a real PostgreSQL instance, that only SCHOOL_ADMIN can
 * create a student (plan section 7d) and that a reference to a nonexistent
 * school/student is reported as 404, never a raw DB error.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentSisIntegrationTest {

    private static final String ADMIN_A = "admin@tenant-a.example";
    private static final String TEACHER_A = "teacher@tenant-a.example";
    private static final String STUDENT_A = "student@tenant-a.example";
    private static final String PARENT_A = "parent@tenant-a.example";
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;
    private UUID studentA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolA = UUID.randomUUID();
        studentA = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Tenant A School')");

            seedUser(st, ADMIN_A, "Admin A", "SCHOOL_ADMIN");
            seedUser(st, TEACHER_A, "Teacher A", "TEACHER");
            seedUser(st, STUDENT_A, "Student User A", "STUDENT");
            seedUser(st, PARENT_A, "Parent User A", "PARENT");

            st.execute("INSERT INTO students (id, school_id, full_name, guardian_name, admission_number, status) VALUES ('"
                    + studentA + "', '" + schoolA + "', 'Existing A', 'Old Guardian', 'ADM-A-1', 'ACTIVE')");
        }
    }

    private void seedUser(Statement st, String email, String fullName, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '" + fullName + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String createBody(UUID schoolId, String admissionNumber) {
        return "{\"schoolId\":\"" + schoolId + "\",\"fullName\":\"New Student\",\"admissionNumber\":\""
                + admissionNumber + "\"}";
    }

    // --- Listing / existence checks ----------------------------------------

    @Test
    void listReturnsStudents() throws Exception {
        var response = mockMvc.perform(get("/api/v1/students").cookie(login(ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("Existing A"))
                .andReturn();

        JsonNode content = JSON.readTree(response.getResponse().getContentAsString()).get("content");
        assertThat(content).allMatch(node -> node.get("fullName").asText().equals("Existing A"));
    }

    @Test
    void getByIdOfANonexistentStudentReturns404NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/students/" + UUID.randomUUID()).cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotCreateStudentAgainstANonexistentSchool() throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), "ADM-A-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateAdmissionNumberReturns409WithClearMessage() throws Exception {
        var result = mockMvc.perform(post("/api/v1/students")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(schoolA, "ADM-A-1"))) // "Existing A" already uses this
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ADM-A-1")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("sql", "constraint", "exception");
    }

    // --- Role-based authorization ----------------------------------------

    @Test
    void schoolAdminCanCreateStudent() throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(schoolA, "ADM-NEW-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void teacherCannotCreateStudentAndGets403() throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(schoolA, "ADM-NEW-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCanStillListStudents() throws Exception {
        mockMvc.perform(get("/api/v1/students").cookie(login(TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void teacherCanStillGetASingleStudentById() throws Exception {
        mockMvc.perform(get("/api/v1/students/" + studentA).cookie(login(TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Existing A"));
    }

    @Test
    void studentsAndParentsCannotGetAStudentRecordDirectlyAndGet403() throws Exception {
        // The full student record (guardian contact included) is staff-only; a
        // student or parent must use /api/v1/me/student or /api/v1/me/children.
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/students/" + studentA).cookie(login(email)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void validationRejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"fullName\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    // --- Edit (PUT) -----------------------------------------------------

    private static final String UPDATE_BODY = "{\"fullName\":\"Existing A (renamed)\","
            + "\"guardianName\":\"New Guardian\",\"guardianContact\":\"+1 222 333\",\"status\":\"ACTIVE\"}";

    @Test
    void schoolAdminCanEditStudentAndAdmissionNumberStaysImmutable() throws Exception {
        mockMvc.perform(put("/api/v1/students/" + studentA)
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        // admissionNumber in the body is ignored -- the DTO has no such field
                        .content("{\"fullName\":\"Existing A (renamed)\",\"guardianName\":\"New Guardian\","
                                + "\"guardianContact\":\"+1 222 333\",\"status\":\"ACTIVE\",\"admissionNumber\":\"HACKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Existing A (renamed)"))
                .andExpect(jsonPath("$.guardianName").value("New Guardian"))
                .andExpect(jsonPath("$.admissionNumber").value("ADM-A-1"));

        mockMvc.perform(get("/api/v1/students/" + studentA).cookie(login(ADMIN_A)))
                .andExpect(jsonPath("$.fullName").value("Existing A (renamed)"))
                .andExpect(jsonPath("$.admissionNumber").value("ADM-A-1"));
    }

    @Test
    void teacherCannotEditStudentAndGets403() throws Exception {
        mockMvc.perform(put("/api/v1/students/" + studentA)
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void editingANonexistentStudentReturns404() throws Exception {
        mockMvc.perform(put("/api/v1/students/" + UUID.randomUUID())
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY))
                .andExpect(status().isNotFound());
    }

    // --- Deactivate (PATCH .../status) --------------------------------

    @Test
    void deactivatingAStudentSetsStatusInactiveAndShowsInTheList() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/status")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        var list = mockMvc.perform(get("/api/v1/students").cookie(login(ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(studentA.toString()))
                .andExpect(jsonPath("$.content[0].status").value("INACTIVE"))
                .andReturn();
        // Soft delete: the row is still there, just INACTIVE.
        assertThat(JSON.readTree(list.getResponse().getContentAsString()).get("content")).hasSize(1);
    }

    @Test
    void teacherCannotDeactivateAStudentAndGets403() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/status")
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isForbidden());
    }
}
