package com.smsapp.staff;

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
import org.springframework.test.web.servlet.ResultActions;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Human Resource > Staff Attendance against a real PostgreSQL instance: the roster of one role, saving and
 * replacing a day's marks, the validation rules, and STAFF_ATTENDANCE_* permission gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffAttendanceIntegrationTest {

    private static final String ADMIN = "sa-admin@school.example";
    private static final String PRINCIPAL = "sa-principal@school.example";
    private static final String TEACHER = "sa-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TODAY = LocalDate.now().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherRoleId;
    private UUID receptionistRoleId;

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
                    + "departments, designations CASCADE");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID principalRole = seedUser(st, PRINCIPAL, "PRINCIPAL");
            teacherRoleId = seedUser(st, TEACHER, "TEACHER");
            receptionistRoleId = insertRole(st, "RECEPTIONIST");
            // Same grants V22/V45 make (other test classes truncate roles/permissions).
            grant(st, adminRole, "STAFF_VIEW", "STAFF_CREATE", "STAFF_EDIT", "STAFF_ATTENDANCE_VIEW", "STAFF_ATTENDANCE_EDIT");
            grant(st, principalRole, "STAFF_VIEW", "STAFF_ATTENDANCE_VIEW");
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

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    /** Adds a staff member through the directory API and returns their profile id. */
    private String addStaff(Cookie admin, String staffId, String name, UUID roleId) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("staffId", staffId);
        body.put("roleId", roleId);
        body.put("firstName", name);
        body.put("email", staffId + "@school.example");
        body.put("gender", "Male");
        body.put("dateOfBirth", "1990-04-12");
        body.put("panNumber", "ABCDE1234F");
        String response = mockMvc.perform(post("/api/v1/staff-members").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("staff").get("id").asText();
    }

    private ResultActions save(Cookie caller, String date, List<Map<String, Object>> entries) throws Exception {
        return mockMvc.perform(put("/api/v1/staff-attendance").cookie(caller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("date", date, "entries", entries))));
    }

    private static Map<String, Object> entry(String staffProfileId, String status, Object... extras) {
        var map = new LinkedHashMap<String, Object>();
        map.put("staffProfileId", staffProfileId);
        map.put("status", status);
        for (int i = 0; i < extras.length; i += 2) {
            map.put((String) extras[i], extras[i + 1]);
        }
        return map;
    }

    @Test
    void rolesListTheRolesStaffCanHold() throws Exception {
        mockMvc.perform(get("/api/v1/staff-attendance/roles").cookie(login(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void rosterListsTheActiveStaffOfTheRoleWithoutMarksUntilSaved() throws Exception {
        Cookie admin = login(ADMIN);
        addStaff(admin, "9002", "Shivam", teacherRoleId);
        addStaff(admin, "9000", "Joe", teacherRoleId);
        addStaff(admin, "9006", "Brandon", receptionistRoleId);

        mockMvc.perform(get("/api/v1/staff-attendance").cookie(admin).param("roleId", teacherRoleId.toString()).param("date", TODAY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].staffId").value("9000"))
                .andExpect(jsonPath("$[0].roleName").value("TEACHER"))
                .andExpect(jsonPath("$[0].status").doesNotExist())
                .andExpect(jsonPath("$[0].source").value("MANUAL"));
        mockMvc.perform(get("/api/v1/staff-attendance").cookie(admin).param("date", TODAY)).andExpect(status().isBadRequest());
    }

    @Test
    void savingMarksTheDayAndSavingAgainReplacesIt() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, "9002", "Shivam", teacherRoleId);
        String jason = addStaff(admin, "90006", "Jason", teacherRoleId);

        save(admin, TODAY, List.of(entry(shivam, "Present", "entryTime", "09:00", "exitTime", "17:30", "note", "On time"),
                entry(jason, "LATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(2));
        mockMvc.perform(get("/api/v1/staff-attendance").cookie(admin).param("roleId", teacherRoleId.toString()).param("date", TODAY))
                .andExpect(jsonPath("$[0].staffId").value("90006"))
                .andExpect(jsonPath("$[0].status").value("LATE"))
                .andExpect(jsonPath("$[0].date").value(TODAY))
                .andExpect(jsonPath("$[1].status").value("PRESENT"))
                .andExpect(jsonPath("$[1].entryTime").value("09:00:00"))
                .andExpect(jsonPath("$[1].exitTime").value("17:30:00"))
                .andExpect(jsonPath("$[1].note").value("On time"));

        // Saving the same day again changes the marks (and clears the times/note that are left out).
        save(admin, TODAY, List.of(entry(shivam, "half_day_second_half"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/staff-attendance").cookie(admin).param("roleId", teacherRoleId.toString()).param("date", TODAY))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("LATE"))
                .andExpect(jsonPath("$[1].status").value("HALF_DAY_SECOND_HALF"))
                .andExpect(jsonPath("$[1].entryTime").doesNotExist())
                .andExpect(jsonPath("$[1].note").doesNotExist());
        // Another day is untouched.
        String yesterday = LocalDate.now().minusDays(1).toString();
        mockMvc.perform(get("/api/v1/staff-attendance").cookie(admin).param("roleId", teacherRoleId.toString()).param("date", yesterday))
                .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test
    void savingValidatesTheEntries() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, "9002", "Shivam", teacherRoleId);

        save(admin, LocalDate.now().plusDays(1).toString(), List.of(entry(shivam, "PRESENT"))).andExpect(status().isBadRequest());
        save(admin, TODAY, List.of()).andExpect(status().isBadRequest());
        save(admin, TODAY, List.of(entry(shivam, "Sleeping"))).andExpect(status().isBadRequest());
        save(admin, TODAY, List.of(entry(shivam, "PRESENT"), entry(shivam, "LATE"))).andExpect(status().isBadRequest());
        save(admin, TODAY, List.of(entry(shivam, "PRESENT", "entryTime", "17:00", "exitTime", "09:00"))).andExpect(status().isBadRequest());
        save(admin, TODAY, List.of(entry(UUID.randomUUID().toString(), "PRESENT"))).andExpect(status().isNotFound());
        save(admin, TODAY, List.of(entry(shivam, "PRESENT", "note", "x".repeat(501)))).andExpect(status().isBadRequest());
        // Nothing was saved by the refused requests.
        mockMvc.perform(get("/api/v1/staff-attendance").cookie(admin).param("roleId", teacherRoleId.toString()).param("date", TODAY))
                .andExpect(jsonPath("$[0].status").doesNotExist());
    }

    @Test
    void inactiveStaffAreNotListedOrMarked() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, "9002", "Shivam", teacherRoleId);
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("UPDATE staff_profiles SET status = 'INACTIVE' WHERE id = '" + shivam + "'");
        }
        mockMvc.perform(get("/api/v1/staff-attendance").cookie(admin).param("roleId", teacherRoleId.toString()).param("date", TODAY))
                .andExpect(jsonPath("$.length()").value(0));
        save(admin, TODAY, List.of(entry(shivam, "PRESENT"))).andExpect(status().isBadRequest());
    }

    @Test
    void permissionsFollowTheStaffAttendanceGrants() throws Exception {
        String shivam = addStaff(login(ADMIN), "9002", "Shivam", teacherRoleId);

        Cookie principal = login(PRINCIPAL);
        mockMvc.perform(get("/api/v1/staff-attendance").cookie(principal).param("roleId", teacherRoleId.toString()).param("date", TODAY))
                .andExpect(status().isOk());
        save(principal, TODAY, List.of(entry(shivam, "PRESENT"))).andExpect(status().isForbidden());

        // A teacher holds the student ATTENDANCE_* permissions in a real school, but not these.
        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/staff-attendance/roles").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff-attendance").cookie(teacher).param("roleId", teacherRoleId.toString()).param("date", TODAY))
                .andExpect(status().isForbidden());
        save(teacher, TODAY, List.of(entry(shivam, "PRESENT"))).andExpect(status().isForbidden());
    }
}
