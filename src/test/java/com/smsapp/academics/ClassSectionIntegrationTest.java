package com.smsapp.academics;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Classes / sections module against a real PostgreSQL instance: SCHOOL_ADMIN-only
 * writes, and a nonexistent class/section reference fails with 404 (plan
 * section 2 / 7c-d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassSectionIntegrationTest {

    private static final String ADMIN_A = "admin@tenant-a.example";
    private static final String TEACHER_A = "teacher@tenant-a.example";
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

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Tenant A School')");

            seedUser(st, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, TEACHER_A, "TEACHER");

            st.execute("INSERT INTO students (id, school_id, full_name, admission_number, status) VALUES ('"
                    + studentA + "', '" + schoolA + "', 'Student A', 'ADM-A', 'ACTIVE')");
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

    /** Creates a class via the API and returns its id. */
    private UUID createClassA(Cookie admin, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/classes").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Class / section writes are SCHOOL_ADMIN only ------------------

    @Test
    void schoolAdminCanCreateAClass() throws Exception {
        mockMvc.perform(post("/api/v1/classes")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Grade 5\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Grade 5"))
                .andExpect(jsonPath("$.sections.length()").value(0));
    }

    @Test
    void teacherCannotCreateAClass() throws Exception {
        mockMvc.perform(post("/api/v1/classes")
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Grade 5\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void schoolAdminCanCreateASectionButTeacherCannot() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("A"))
                .andExpect(jsonPath("$.classId").value(classA.toString()));

        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections")
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"B\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Listing / existence checks --------------------------------------

    @Test
    void listClassesNestsSections() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Grade 5"))
                .andExpect(jsonPath("$[0].sections.length()").value(1))
                .andExpect(jsonPath("$[0].sections[0].name").value("A"));
    }

    @Test
    void cannotCreateSectionUnderANonexistentClass() throws Exception {
        mockMvc.perform(post("/api/v1/classes/" + UUID.randomUUID() + "/sections")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Y\"}"))
                .andExpect(status().isNotFound());
    }

    // --- Student <-> section assignment -----------------------------

    private UUID createSectionA() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        var result = mockMvc.perform(post("/api/v1/classes/" + classA + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void schoolAdminCanAssignAStudentToASection() throws Exception {
        UUID sectionA = createSectionA();

        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionId").value(sectionA.toString()));
    }

    @Test
    void teacherCannotAssignAStudentToASection() throws Exception {
        UUID sectionA = createSectionA();

        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section")
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assigningAStudentToANonexistentSectionReturns404() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentsCanBeFilteredBySection() throws Exception {
        UUID sectionA = createSectionA();
        Cookie admin = login(ADMIN_A);
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/students").param("sectionId", sectionA.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(studentA.toString()));

        mockMvc.perform(get("/api/v1/students").param("sectionId", UUID.randomUUID().toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }
}
