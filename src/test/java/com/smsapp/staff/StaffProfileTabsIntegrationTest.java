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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Payroll, Leaves and Attendance tabs of a staff profile, and its disable / reset-password actions. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffProfileTabsIntegrationTest {

    private static final String ADMIN = "pt-admin@school.example";
    private static final String VIEWER = "pt-viewer@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherRoleId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, staff_profiles, staff_attendance, "
                    + "payroll_records, leave_requests, departments, designations CASCADE");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID viewerRole = seedUser(st, VIEWER, "PRINCIPAL");
            teacherRoleId = insertRole(st, "TEACHER");
            grant(st, adminRole, "STAFF_VIEW", "STAFF_CREATE", "STAFF_EDIT", "STAFF_ATTENDANCE_VIEW", "STAFF_ATTENDANCE_EDIT",
                    "PAYROLL_VIEW", "LEAVE_VIEW");
            grant(st, viewerRole, "STAFF_VIEW");
        }
    }

    private UUID insertRole(Statement st, String name) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + name + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = insertRole(st, role);
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return roleId;
    }

    private static void grant(Statement st, UUID roleId, String... permissions) throws SQLException {
        for (String name : permissions) {
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + roleId + "', id FROM permissions "
                    + "WHERE name = '" + name + "'");
        }
    }

    private Cookie login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String addStaff(Cookie admin, String staffId) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("staffId", staffId);
        body.put("roleId", teacherRoleId);
        body.put("firstName", "Shivam");
        body.put("email", staffId + "@school.example");
        body.put("gender", "Male");
        body.put("dateOfBirth", "1990-04-12");
        body.put("panNumber", "ABCDE1234F");
        String response = mockMvc.perform(post("/api/v1/staff-members").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("staff").get("id").asText();
    }

    @Test
    void attendanceTabCountsTheYearsMarks() throws Exception {
        Cookie admin = login(ADMIN, "secret");
        String id = addStaff(admin, "9002");
        LocalDate day = LocalDate.of(LocalDate.now().getYear(), 3, 14);
        mockMvc.perform(put("/api/v1/staff-attendance").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("date", day.toString(),
                                "entries", List.of(Map.of("staffProfileId", id, "status", "LATE"))))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/staff-members/" + id + "/attendance").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.late").value(1))
                .andExpect(jsonPath("$.present").value(0))
                .andExpect(jsonPath("$.days['3-14']").value("LATE"));
    }

    @Test
    void payrollAndLeavesTabsStartEmpty() throws Exception {
        Cookie admin = login(ADMIN, "secret");
        String id = addStaff(admin, "9002");

        mockMvc.perform(get("/api/v1/staff-members/" + id + "/payroll").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payslips.length()").value(0))
                .andExpect(jsonPath("$.totalNetPaid").value(0));
        mockMvc.perform(get("/api/v1/staff-members/" + id + "/leaves").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.length()").value(0));
    }

    @Test
    void tabsNeedTheirModulePermission() throws Exception {
        String id = addStaff(login(ADMIN, "secret"), "9002");
        Cookie viewer = login(VIEWER, "secret");
        mockMvc.perform(get("/api/v1/staff-members/" + id + "/payroll").cookie(viewer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff-members/" + id + "/leaves").cookie(viewer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff-members/" + id + "/attendance").cookie(viewer)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/staff-members/" + id + "/status").cookie(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isForbidden());
    }

    @Test
    void disablingStopsLoginAndMovesTheStaffToTheDisabledList() throws Exception {
        Cookie admin = login(ADMIN, "secret");
        String id = addStaff(admin, "9002");
        String password = JSON.readTree(mockMvc.perform(post("/api/v1/staff-members/" + id + "/reset-password").cookie(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("temporaryPassword").asText();
        login("9002@school.example", password);

        mockMvc.perform(patch("/api/v1/staff-members/" + id + "/status").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        mockMvc.perform(get("/api/v1/staff-members").cookie(admin)).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/staff-members").cookie(admin).param("status", "INACTIVE"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"9002@school.example\",\"password\":\"" + password + "\"}")).andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/staff-members/" + id + "/status").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}")).andExpect(jsonPath("$.status").value("ACTIVE"));
        login("9002@school.example", password);
    }
}
