package com.smsapp.announcement;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Announcements against a real PostgreSQL instance (plan section 2,
 * "Communication"). Covers: SCHOOL_ADMIN-only create/delete (TEACHER/STUDENT/
 * PARENT can read but get 403 on writes), a nonexistent announcement id on
 * delete -> 404, and that every role's dashboard carries the recent
 * announcements.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementIntegrationTest {

    private static final String ADMIN_A = "admin@ann-a.example";
    private static final String TEACHER_A = "teacher@ann-a.example";
    private static final String STUDENT_A = "student@ann-a.example";
    private static final String PARENT_A = "parent@ann-a.example";
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID schoolAId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, announcements, audit_log CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolAId + "', 'Tenant A School')");

            seedUser(st, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, TEACHER_A, "TEACHER");
            seedUser(st, STUDENT_A, "STUDENT");
            seedUser(st, PARENT_A, "PARENT");
        }
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return userId;
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID postAnnouncement(Cookie session, String title, String body) throws Exception {
        var result = mockMvc.perform(post("/api/v1/announcements").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private JsonNode list(Cookie session) throws Exception {
        var result = mockMvc.perform(get("/api/v1/announcements").cookie(session))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // --- create: SCHOOL_ADMIN only -------------------------------

    @Test
    void schoolAdminCanPostAnAnnouncement() throws Exception {
        mockMvc.perform(post("/api/v1/announcements").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Exams start Monday\",\"body\":\"Bring your hall ticket.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Exams start Monday"))
                .andExpect(jsonPath("$.body").value("Bring your hall ticket."))
                .andExpect(jsonPath("$.createdBy").isNotEmpty());
    }

    @Test
    void teacherStudentAndParentCannotPost() throws Exception {
        String body = "{\"title\":\"X\",\"body\":\"Y\"}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/announcements").cookie(login(email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    // --- read: everyone --------------------------

    @Test
    void everyRoleCanReadTheListNewestFirst() throws Exception {
        Cookie admin = login(ADMIN_A);
        postAnnouncement(admin, "First", "one");
        postAnnouncement(admin, "Second", "two");

        for (String email : new String[] {ADMIN_A, TEACHER_A, STUDENT_A, PARENT_A}) {
            JsonNode page = list(login(email));
            assertThat(page.get("content")).hasSize(2);
            assertThat(page.get("content").get(0).get("title").asText()).isEqualTo("Second"); // newest first
            assertThat(page.get("content").get(1).get("title").asText()).isEqualTo("First");
        }
    }

    // --- delete: SCHOOL_ADMIN only ----------

    @Test
    void schoolAdminCanDeleteAnAnnouncement() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID id = postAnnouncement(admin, "Temporary", "will be removed");

        mockMvc.perform(delete("/api/v1/announcements/" + id).cookie(admin))
                .andExpect(status().isNoContent());

        assertThat(list(admin).get("content")).isEmpty();
    }

    @Test
    void teacherStudentAndParentCannotDelete() throws Exception {
        UUID id = postAnnouncement(login(ADMIN_A), "Keep me", "body");

        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(delete("/api/v1/announcements/" + id).cookie(login(email)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void cannotDeleteANonexistentAnnouncement() throws Exception {
        mockMvc.perform(delete("/api/v1/announcements/" + UUID.randomUUID()).cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- dashboard: recent announcements for every role -------

    @Test
    void everyRolesDashboardCarriesTheRecentAnnouncements() throws Exception {
        Cookie admin = login(ADMIN_A);
        postAnnouncement(admin, "Holiday Friday", "school closed");

        for (String email : new String[] {ADMIN_A, TEACHER_A, STUDENT_A, PARENT_A}) {
            var result = mockMvc.perform(get("/api/v1/dashboard/summary").cookie(login(email)))
                    .andExpect(status().isOk()).andReturn();
            JsonNode announcements = JSON.readTree(result.getResponse().getContentAsString()).get("announcements");
            assertThat(announcements).as("announcements for " + email).hasSize(1);
            assertThat(announcements.get(0).get("title").asText()).isEqualTo("Holiday Friday");
        }
    }
}
