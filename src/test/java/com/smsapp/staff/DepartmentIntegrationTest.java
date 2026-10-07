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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Human Resource > Department against a real PostgreSQL instance: add, rename, delete and DEPARTMENT_MANAGE gating. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DepartmentIntegrationTest {

    private static final String ADMIN = "dp-admin@school.example";
    private static final String TEACHER = "dp-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID academicId;
    private UUID libraryId;
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
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, staff_profiles, departments, "
                    + "designations CASCADE");
            academicId = UUID.randomUUID();
            libraryId = UUID.randomUUID();
            st.execute("INSERT INTO departments (id, name) VALUES ('" + academicId + "', 'Academic'), ('" + libraryId + "', 'Library')");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, TEACHER, "TEACHER");
            teacherRoleId = UUID.randomUUID();
            st.execute("INSERT INTO roles (id, name) VALUES ('" + teacherRoleId + "', 'STAFF_TEACHER')");
            grant(st, adminRole, "DEPARTMENT_MANAGE", "STAFF_VIEW", "STAFF_CREATE");
        }
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
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

    private ResultActions postDepartment(Cookie caller, String name) throws Exception {
        return mockMvc.perform(post("/api/v1/hr/departments").cookie(caller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("name", name))));
    }

    private ResultActions putDepartment(Cookie caller, UUID id, String name) throws Exception {
        return mockMvc.perform(put("/api/v1/hr/departments/" + id).cookie(caller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("name", name))));
    }

    @Test
    void departmentsAreListedByNameAndCanBeAdded() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(get("/api/v1/hr/departments").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Academic"));
        postDepartment(admin, "  Sports ").andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Sports"));
        mockMvc.perform(get("/api/v1/hr/departments").cookie(admin)).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void namesAreRequiredAndUniqueIgnoringCase() throws Exception {
        Cookie admin = login(ADMIN);
        postDepartment(admin, " ").andExpect(status().isBadRequest());
        postDepartment(admin, "x".repeat(101)).andExpect(status().isBadRequest());
        postDepartment(admin, "library").andExpect(status().isConflict());
        putDepartment(admin, academicId, "LIBRARY").andExpect(status().isConflict());
        putDepartment(admin, academicId, "ACADEMIC").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("ACADEMIC"));
        putDepartment(admin, UUID.randomUUID(), "Anything").andExpect(status().isNotFound());
    }

    @Test
    void renamingRenamesItOnStaffAndADepartmentWithStaffCannotBeDeleted() throws Exception {
        Cookie admin = login(ADMIN);
        var body = new LinkedHashMap<String, Object>();
        body.put("staffId", "9002");
        body.put("roleId", teacherRoleId);
        body.put("departmentId", academicId);
        body.put("firstName", "Shivam");
        body.put("email", "9002@school.example");
        body.put("gender", "Male");
        body.put("dateOfBirth", "1990-04-12");
        body.put("panNumber", "ABCDE1234F");
        mockMvc.perform(post("/api/v1/staff-members").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body))).andExpect(status().isCreated());

        putDepartment(admin, academicId, "Teaching").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/staff-members").cookie(admin))
                .andExpect(jsonPath("$[0].departmentName").value("Teaching"));
        mockMvc.perform(delete("/api/v1/hr/departments/" + academicId).cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/hr/departments/" + libraryId).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/hr/departments").cookie(admin)).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete("/api/v1/hr/departments/" + libraryId).cookie(admin)).andExpect(status().isNotFound());
    }

    @Test
    void managingNeedsThePermission() throws Exception {
        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/hr/departments").cookie(teacher)).andExpect(status().isForbidden());
        postDepartment(teacher, "Sports").andExpect(status().isForbidden());
        putDepartment(teacher, academicId, "Sports").andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/hr/departments/" + academicId).cookie(teacher)).andExpect(status().isForbidden());
    }
}
