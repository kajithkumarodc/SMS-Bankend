package com.smsapp.user;

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
import java.sql.ResultSet;
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
 * RBAC Phase 1 against a real PostgreSQL instance: role/permission management
 * and local user provisioning are SCHOOL_ADMIN/SUPER_ADMIN only, a newly
 * created user can log in with the password they were given, and password
 * reset/change work without any email or SMS provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RbacIntegrationTest {

    private static final String ADMIN = "rbac-admin@school.example";
    private static final String TEACHER = "rbac-teacher@school.example";
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
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, "
                    + "students, attendance_records, sections, classes, academic_years CASCADE");

            seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, TEACHER, "TEACHER");

            // The V22 migration seeds a permission catalog once, but every other
            // integration test's @BeforeEach truncates `permissions` too (shared
            // test DB, and Flyway never re-runs an already-applied migration) --
            // so by the time this test class runs, an earlier test class may
            // already have wiped it. Self-seed what these tests need, same as
            // roles/users are self-seeded, rather than depending on migration
            // seed data surviving across the whole suite.
            st.execute("INSERT INTO permissions (id, name) VALUES ('" + UUID.randomUUID() + "', 'STUDENT_VIEW')");
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

    /** The TEACHER role id seeded by {@link #seed()}, looked up fresh (created with a random id each run). */
    private UUID teacherRoleId() throws SQLException {
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM roles WHERE name = 'TEACHER'")) {
            rs.next();
            return UUID.fromString(rs.getString(1));
        }
    }

    private Cookie login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private Cookie loginAsAdmin() throws Exception {
        return login(ADMIN, "secret");
    }

    // --- Roles / permissions ------------------------------------------

    @Test
    void schoolAdminCanCreateARoleAndSeePermissionCatalog() throws Exception {
        Cookie admin = loginAsAdmin();

        mockMvc.perform(post("/api/v1/roles").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"EXAM_COORDINATOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("EXAM_COORDINATOR"));

        mockMvc.perform(get("/api/v1/roles").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'EXAM_COORDINATOR')].name").value("EXAM_COORDINATOR"));

        mockMvc.perform(get("/api/v1/permissions").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'STUDENT_VIEW')].name").value("STUDENT_VIEW"));
    }

    @Test
    void teacherCannotManageRoles() throws Exception {
        mockMvc.perform(get("/api/v1/roles").cookie(login(TEACHER, "secret")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/roles").cookie(login(TEACHER, "secret"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void schoolAdminCanAssignPermissionsToARole() throws Exception {
        Cookie admin = loginAsAdmin();
        UUID roleId = teacherRoleId();

        String permJson = mockMvc.perform(get("/api/v1/permissions").cookie(admin))
                .andReturn().getResponse().getContentAsString();
        String permId = null;
        var it = JSON.readTree(permJson).elements();
        while (it.hasNext()) {
            var node = it.next();
            if ("STUDENT_VIEW".equals(node.get("name").asText())) {
                permId = node.get("id").asText();
                break;
            }
        }
        assertThat(permId).isNotNull();

        mockMvc.perform(put("/api/v1/roles/" + roleId + "/permissions").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissionIds\":[\"" + permId + "\"]}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/roles").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'TEACHER')].permissionNames[0]").value("STUDENT_VIEW"));
    }

    // --- Users -----------------------------------------------------------

    @Test
    void schoolAdminCanCreateAUserWhoCanThenLogIn() throws Exception {
        Cookie admin = loginAsAdmin();
        UUID roleId = teacherRoleId();

        var result = mockMvc.perform(post("/api/v1/users").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new.teacher@school.example\",\"fullName\":\"New Teacher\","
                                + "\"roleIds\":[\"" + roleId + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andExpect(jsonPath("$.generatedPassword").isNotEmpty())
                .andReturn();

        String generatedPassword = JSON.readTree(result.getResponse().getContentAsString())
                .get("generatedPassword").asText();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new.teacher@school.example\",\"password\":\"" + generatedPassword + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void creatingAUserWithADuplicateEmailReturns409() throws Exception {
        mockMvc.perform(post("/api/v1/users").cookie(loginAsAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN + "\",\"fullName\":\"Dup\",\"roleIds\":[\""
                                + teacherRoleId() + "\"]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void teacherCannotCreateUsers() throws Exception {
        mockMvc.perform(post("/api/v1/users").cookie(login(TEACHER, "secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@school.example\",\"fullName\":\"X\",\"roleIds\":[\""
                                + teacherRoleId() + "\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void schoolAdminCanResetAUsersPasswordAndTheyCanLogInWithIt() throws Exception {
        Cookie admin = loginAsAdmin();

        var result = mockMvc.perform(patch("/api/v1/users/" + userId(TEACHER) + "/reset-password").cookie(admin))
                .andExpect(status().isOk())
                .andReturn();
        String temp = JSON.readTree(result.getResponse().getContentAsString()).get("temporaryPassword").asText();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + TEACHER + "\",\"password\":\"" + temp + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aUserCanChangeTheirOwnPassword() throws Exception {
        Cookie teacher = login(TEACHER, "secret");

        mockMvc.perform(post("/api/v1/me/change-password").cookie(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"secret\",\"newPassword\":\"brandNewPass1\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + TEACHER + "\",\"password\":\"brandNewPass1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void changingPasswordWithWrongCurrentPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/me/change-password").cookie(login(TEACHER, "secret"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"nope\",\"newPassword\":\"brandNewPass1\"}"))
                .andExpect(status().isBadRequest());
    }

    private UUID userId(String email) throws SQLException {
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM users WHERE email = '" + email + "'")) {
            rs.next();
            return UUID.fromString(rs.getString(1));
        }
    }
}
