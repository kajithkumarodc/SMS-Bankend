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
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Academics > Assign Class Teacher against a real PostgreSQL instance. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassTeacherIntegrationTest {

    private static final String ADMIN = "ct-admin@school.example";
    private static final String TEACHER = "ct-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie admin;
    private UUID class1;
    private UUID class2;
    private UUID section1A;
    private UUID section2A;
    private UUID staffRoleId;
    private String shivam;
    private String jason;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws Exception {
        UUID schoolId = UUID.randomUUID();
        class1 = UUID.randomUUID();
        class2 = UUID.randomUUID();
        section1A = UUID.randomUUID();
        section2A = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, staff_profiles, classes, sections, class_teachers, "
                    + "departments, designations CASCADE");
            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Test School')");
            st.execute("INSERT INTO classes (id, school_id, name, sort_order) VALUES ('" + class1 + "', '" + schoolId + "', 'Class 1', 1), ('"
                    + class2 + "', '" + schoolId + "', 'Class 2', 2)");
            st.execute("INSERT INTO sections (id, class_id, name) VALUES ('" + section1A + "', '" + class1 + "', 'A'), ('" + section2A + "', '" + class2 + "', 'A')");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, TEACHER, "TEACHER");
            staffRoleId = UUID.randomUUID();
            st.execute("INSERT INTO roles (id, name) VALUES ('" + staffRoleId + "', 'STAFF_TEACHER')");
            for (String name : new String[] {"STAFF_VIEW", "STAFF_CREATE", "STAFF_EDIT"}) {
                st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + adminRole + "', id FROM permissions WHERE name = '" + name + "'");
            }
        }
        admin = login(ADMIN);
        shivam = addStaff("9002", "Shivam");
        jason = addStaff("90006", "Jason");
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

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
    }

    private String addStaff(String staffId, String name) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("staffId", staffId);
        body.put("roleId", staffRoleId);
        body.put("firstName", name);
        body.put("email", staffId + "@school.example");
        body.put("gender", "Male");
        body.put("dateOfBirth", "1990-04-12");
        body.put("panNumber", "ABCDE1234F");
        return JSON.readTree(mockMvc.perform(post("/api/v1/staff-members").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("staff").get("id").asText();
    }

    private ResultActions assign(UUID section, List<String> staff) throws Exception {
        return mockMvc.perform(put("/api/v1/academics/class-teachers/" + section).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("staffProfileIds", staff))));
    }

    @Test
    void teachersAreAssignedReplacedAndListedInClassOrder() throws Exception {
        assign(section2A, List.of(jason)).andExpect(status().isOk());
        assign(section1A, List.of(shivam, jason)).andExpect(status().isOk())
                .andExpect(jsonPath("$.className").value("Class 1")).andExpect(jsonPath("$.sectionName").value("A"))
                .andExpect(jsonPath("$.teachers.length()").value(2)).andExpect(jsonPath("$.teachers[0].staffId").value("9002"));
        mockMvc.perform(get("/api/v1/academics/class-teachers").cookie(admin)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].className").value("Class 1")).andExpect(jsonPath("$[1].className").value("Class 2"));
        // Assigning again replaces the section's teachers.
        assign(section1A, List.of(jason)).andExpect(jsonPath("$.teachers.length()").value(1)).andExpect(jsonPath("$.teachers[0].name").value("Jason"));
        mockMvc.perform(delete("/api/v1/academics/class-teachers/" + section1A).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/academics/class-teachers").cookie(admin)).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete("/api/v1/academics/class-teachers/" + section1A).cookie(admin)).andExpect(status().isNotFound());
    }

    @Test
    void assignmentIsValidated() throws Exception {
        assign(section1A, List.of()).andExpect(status().isBadRequest());
        assign(UUID.randomUUID(), List.of(shivam)).andExpect(status().isNotFound());
        assign(section1A, List.of(UUID.randomUUID().toString())).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/staff-members/" + jason + "/status").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isOk());
        assign(section1A, List.of(jason)).andExpect(status().isBadRequest());
    }

    @Test
    void onlySchoolAdminsCanManageClassTeachers() throws Exception {
        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/academics/class-teachers").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/academics/class-teachers/" + section1A).cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffProfileIds\":[\"" + shivam + "\"]}")).andExpect(status().isForbidden());
    }
}
