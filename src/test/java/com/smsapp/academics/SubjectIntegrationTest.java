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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Subjects + class-subject assignment against a real PostgreSQL instance:
 * SCHOOL_ADMIN-only writes, clean 409 on duplicate name / assignment, and a
 * nonexistent class/subject reference fails with 404 (plan section 2 / 7c-d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubjectIntegrationTest {

    private static final String ADMIN_A = "admin@tenant-a.example";
    private static final String TEACHER_A = "teacher@tenant-a.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolA = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, class_subjects, subjects, sections, classes CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Tenant A School')");

            seedUser(st, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, TEACHER_A, "TEACHER");
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

    private UUID id(String responseBody) throws Exception {
        return UUID.fromString(JSON.readTree(responseBody).get("id").asText());
    }

    private UUID createSubjectA(Cookie admin, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/subjects").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return id(result.getResponse().getContentAsString());
    }

    private UUID createClassA(Cookie admin, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/classes").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return id(result.getResponse().getContentAsString());
    }

    // --- Subject creation --------------------------------------------

    @Test
    void schoolAdminCanCreateASubjectButTeacherCannot() throws Exception {
        mockMvc.perform(post("/api/v1/subjects").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Mathematics\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Mathematics"))
                .andExpect(jsonPath("$.schoolId").value(schoolA.toString()));

        mockMvc.perform(post("/api/v1/subjects").cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Science\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateSubjectNameReturns409() throws Exception {
        Cookie admin = login(ADMIN_A);
        createSubjectA(admin, "Mathematics");

        var result = mockMvc.perform(post("/api/v1/subjects").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Mathematics\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Mathematics")))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("sql", "constraint", "exception");
    }

    @Test
    void listSubjectsReturnsCreatedSubjects() throws Exception {
        Cookie admin = login(ADMIN_A);
        createSubjectA(admin, "Mathematics");

        mockMvc.perform(get("/api/v1/subjects").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Mathematics"));
    }

    // --- Class-subject assignment -----------------------------------

    @Test
    void schoolAdminCanAssignASubjectToAClassButTeacherCannot() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        UUID subjectA = createSubjectA(admin, "Mathematics");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(subjectA.toString()))
                .andExpect(jsonPath("$.name").value("Mathematics"));

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assigningTheSameSubjectTwiceReturns409() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        UUID subjectA = createSubjectA(admin, "Mathematics");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotAssignANonexistentSubjectToOwnClass() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotAssignOwnSubjectToANonexistentClass() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID subjectA = createSubjectA(admin, "Mathematics");

        mockMvc.perform(post("/api/v1/classes/" + UUID.randomUUID() + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listClassSubjectsReturnsAssignedSubjects() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        UUID math = createSubjectA(admin, "Mathematics");
        createSubjectA(admin, "Science"); // exists but not assigned

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + math + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/classes/" + classA + "/subjects").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Mathematics"));
    }

    @Test
    void listClassSubjectsForANonexistentClassReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/classes/" + UUID.randomUUID() + "/subjects").cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }
}
