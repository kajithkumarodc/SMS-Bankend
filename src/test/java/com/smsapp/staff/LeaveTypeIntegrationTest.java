package com.smsapp.staff;

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
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Human Resource > Leave Type against a real PostgreSQL instance: add, rename, delete, and LEAVE_TYPE_MANAGE gating. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LeaveTypeIntegrationTest {

    private static final String ADMIN = "lt-admin@school.example";
    private static final String TEACHER = "lt-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID adminUserId;
    private UUID medicalId;
    private UUID casualId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
    }

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, leave_requests, leave_types CASCADE");
            medicalId = UUID.randomUUID();
            casualId = UUID.randomUUID();
            st.execute("INSERT INTO leave_types (id, name) VALUES ('" + medicalId + "', 'Medical Leave'), ('" + casualId
                    + "', 'Casual Leave')");
            adminUserId = seedUser(st, ADMIN, "SCHOOL_ADMIN", "LEAVE_TYPE_MANAGE", "LEAVE_VIEW");
            seedUser(st, TEACHER, "TEACHER", "LEAVE_VIEW", "LEAVE_CREATE");
        }
    }

    private UUID seedUser(Statement st, String email, String role, String... permissions) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        for (String name : permissions) {
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + roleId + "', id FROM permissions "
                    + "WHERE name = '" + name + "'");
        }
        return userId;
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private ResultActions postType(Cookie caller, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/hr/leave-types").cookie(caller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("name", name))));
    }

    private ResultActions putType(Cookie caller, UUID id, String name) throws Exception {
        return mockMvc.perform(put("/api/v1/hr/leave-types/" + id).cookie(caller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("name", name))));
    }

    @Test
    void typesAreListedByNameAndCanBeAdded() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(get("/api/v1/hr/leave-types").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Casual Leave"));
        postType(admin, "  Study Leave ").andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Study Leave"));
        mockMvc.perform(get("/api/v1/hr/leave-types").cookie(admin)).andExpect(jsonPath("$.length()").value(3));
        // The leave pages offer the new type straight away.
        mockMvc.perform(get("/api/v1/hr/leave-requests/options").cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.leaveTypes.length()").value(3));
    }

    @Test
    void namesAreRequiredAndUniqueIgnoringCase() throws Exception {
        Cookie admin = login(ADMIN);
        postType(admin, " ").andExpect(status().isBadRequest());
        postType(admin, "x".repeat(101)).andExpect(status().isBadRequest());
        postType(admin, "medical leave").andExpect(status().isConflict());
        putType(admin, casualId, "MEDICAL LEAVE").andExpect(status().isConflict());
        // Renaming a type to its own name (or another case of it) is fine.
        putType(admin, casualId, "CASUAL LEAVE").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("CASUAL LEAVE"));
        putType(admin, UUID.randomUUID(), "Anything").andExpect(status().isNotFound());
    }

    @Test
    void renamingATypeRenamesItOnItsLeaveRequests() throws Exception {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("INSERT INTO leave_requests (staff_user_id, leave_type, leave_type_id, start_date, end_date, status, days, apply_date) "
                    + "VALUES ('" + adminUserId + "', 'Medical Leave', '" + medicalId + "', '2026-10-05', '2026-10-05', 'PENDING', 1, CURRENT_DATE)");
        }
        putType(login(ADMIN), medicalId, "Health Leave").andExpect(status().isOk());
        try (Connection connection = connect(); Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT leave_type FROM leave_requests")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("Health Leave");
        }
    }

    @Test
    void aTypeInUseCannotBeDeletedButAnUnusedOneCan() throws Exception {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("INSERT INTO leave_requests (staff_user_id, leave_type, leave_type_id, start_date, end_date, status, days, apply_date) "
                    + "VALUES ('" + adminUserId + "', 'Medical Leave', '" + medicalId + "', '2026-10-05', '2026-10-05', 'PENDING', 1, CURRENT_DATE)");
        }
        Cookie admin = login(ADMIN);
        mockMvc.perform(delete("/api/v1/hr/leave-types/" + medicalId).cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/hr/leave-types/" + casualId).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/hr/leave-types/" + casualId).cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/hr/leave-types").cookie(admin)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void onlyThoseWithTheManagePermissionCanUseIt() throws Exception {
        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/hr/leave-types").cookie(teacher)).andExpect(status().isForbidden());
        postType(teacher, "Study Leave").andExpect(status().isForbidden());
        putType(teacher, casualId, "Other").andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/hr/leave-types/" + casualId).cookie(teacher)).andExpect(status().isForbidden());
    }
}
