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
 * Announcements against a real PostgreSQL instance with RLS (plan section 2,
 * "Communication"). Covers: tenant isolation on the list AND the dashboard,
 * SCHOOL_ADMIN-only create/delete (TEACHER/STUDENT/PARENT can read but get 403
 * on writes), cross-tenant delete -> 404, and that every role's dashboard carries
 * the recent announcements.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementIntegrationTest {

    private static final String SCHOOL_A = "ann-a";
    private static final String SCHOOL_B = "ann-b";
    private static final String ADMIN_A = "admin@ann-a.example";
    private static final String TEACHER_A = "teacher@ann-a.example";
    private static final String STUDENT_A = "student@ann-a.example";
    private static final String PARENT_A = "parent@ann-a.example";
    private static final String ADMIN_B = "admin@ann-b.example";
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID tenantAId;
    private UUID tenantBId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        UUID schoolAId = UUID.randomUUID();
        UUID schoolBId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, announcements, audit_log CASCADE");

            seedTenant(st, tenantAId, "Tenant A", SCHOOL_A, schoolAId);
            seedTenant(st, tenantBId, "Tenant B", SCHOOL_B, schoolBId);

            UUID adminB = seedUser(st, tenantBId, ADMIN_B, "SCHOOL_ADMIN");
            seedUser(st, tenantAId, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, tenantAId, TEACHER_A, "TEACHER");
            seedUser(st, tenantAId, STUDENT_A, "STUDENT");
            seedUser(st, tenantAId, PARENT_A, "PARENT");

            // Tenant B already has an announcement -- tenant A must never see it.
            seedAnnouncement(st, tenantBId, adminB, "Tenant B only", "secret to B");
        }
    }

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

    private static void seedAnnouncement(Statement st, UUID tenantId, UUID createdBy, String title, String body)
            throws SQLException {
        st.execute("INSERT INTO announcements (id, tenant_id, title, body, created_by) VALUES ('"
                + UUID.randomUUID() + "', '" + tenantId + "', '" + title + "', '" + body + "', '" + createdBy + "')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
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
        mockMvc.perform(post("/api/v1/announcements").cookie(login(SCHOOL_A, ADMIN_A))
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
            mockMvc.perform(post("/api/v1/announcements").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    // --- read: everyone in the tenant --------------------------

    @Test
    void everyRoleInTheTenantCanReadTheListNewestFirst() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        postAnnouncement(admin, "First", "one");
        postAnnouncement(admin, "Second", "two");

        for (String email : new String[] {ADMIN_A, TEACHER_A, STUDENT_A, PARENT_A}) {
            JsonNode page = list(login(SCHOOL_A, email));
            assertThat(page.get("content")).hasSize(2);
            assertThat(page.get("content").get(0).get("title").asText()).isEqualTo("Second"); // newest first
            assertThat(page.get("content").get(1).get("title").asText()).isEqualTo("First");
        }
    }

    // --- tenant isolation -------------------------------------

    @Test
    void tenantBsAnnouncementsNeverAppearInTenantAsListOrDashboard() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        postAnnouncement(adminA, "A notice", "for A");

        // List: only tenant A's own.
        JsonNode aList = list(adminA);
        assertThat(aList.get("content")).hasSize(1);
        assertThat(aList.get("content").get(0).get("title").asText()).isEqualTo("A notice");

        // Dashboard: only tenant A's own.
        var dash = mockMvc.perform(get("/api/v1/dashboard/summary").cookie(adminA))
                .andExpect(status().isOk()).andReturn();
        JsonNode announcements = JSON.readTree(dash.getResponse().getContentAsString()).get("announcements");
        assertThat(announcements).hasSize(1);
        assertThat(announcements.get(0).get("title").asText()).isEqualTo("A notice");

        // Tenant B still sees only its own seeded one.
        JsonNode bList = list(login(SCHOOL_B, ADMIN_B));
        assertThat(bList.get("content")).hasSize(1);
        assertThat(bList.get("content").get(0).get("title").asText()).isEqualTo("Tenant B only");
    }

    // --- delete: SCHOOL_ADMIN only, cross-tenant 404 ----------

    @Test
    void schoolAdminCanDeleteAnAnnouncement() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID id = postAnnouncement(admin, "Temporary", "will be removed");

        mockMvc.perform(delete("/api/v1/announcements/" + id).cookie(admin))
                .andExpect(status().isNoContent());

        assertThat(list(admin).get("content")).isEmpty();
    }

    @Test
    void teacherStudentAndParentCannotDelete() throws Exception {
        UUID id = postAnnouncement(login(SCHOOL_A, ADMIN_A), "Keep me", "body");

        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(delete("/api/v1/announcements/" + id).cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void cannotDeleteAnotherTenantsAnnouncement() throws Exception {
        UUID aId = postAnnouncement(login(SCHOOL_A, ADMIN_A), "A's", "body");

        // Tenant B admin -> 404, never leaks that it exists.
        mockMvc.perform(delete("/api/v1/announcements/" + aId).cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isNotFound());

        // And it is still there for tenant A.
        assertThat(list(login(SCHOOL_A, ADMIN_A)).get("content")).hasSize(1);
    }

    // --- dashboard: recent announcements for every role -------

    @Test
    void everyRolesDashboardCarriesTheRecentAnnouncements() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        postAnnouncement(admin, "Holiday Friday", "school closed");

        for (String email : new String[] {ADMIN_A, TEACHER_A, STUDENT_A, PARENT_A}) {
            var result = mockMvc.perform(get("/api/v1/dashboard/summary").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isOk()).andReturn();
            JsonNode announcements = JSON.readTree(result.getResponse().getContentAsString()).get("announcements");
            assertThat(announcements).as("announcements for " + email).hasSize(1);
            assertThat(announcements.get(0).get("title").asText()).isEqualTo("Holiday Friday");
        }
    }
}
