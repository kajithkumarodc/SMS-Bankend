package com.smsapp.frontoffice;

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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Setup Front Office against a real PostgreSQL instance: add/edit/delete in the four lists, case-insensitive
 * duplicate names, deactivate-instead-of-delete for entries in use (and restoring them), and FRONT_OFFICE_SETUP
 * gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrontOfficeSetupIntegrationTest {

    private static final String ADMIN = "fs-admin@school.example";
    private static final String RECEPTIONIST = "fs-receptionist@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolEventsId;
    private UUID staffId;

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
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, front_office_purposes, "
                    + "complaint_types, enquiry_sources, enquiry_references, visitors, complaints, admission_enquiries CASCADE");
            schoolEventsId = UUID.randomUUID();
            staffId = UUID.randomUUID();
            st.execute("INSERT INTO front_office_purposes (id, name) VALUES ('" + schoolEventsId + "', 'School Events'), "
                    + "(gen_random_uuid(), 'Marketing')");
            st.execute("INSERT INTO enquiry_references (id, name) VALUES ('" + staffId + "', 'Staff')");
            st.execute("INSERT INTO complaint_types (id, name) VALUES (gen_random_uuid(), 'Fees')");
            st.execute("INSERT INTO enquiry_sources (id, name) VALUES (gen_random_uuid(), 'Advertisement')");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, RECEPTIONIST, "RECEPTIONIST");
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), 'FRONT_OFFICE_SETUP')");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + adminRole + "', id "
                    + "FROM permissions WHERE name = 'FRONT_OFFICE_SETUP'");
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

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private static String item(String name, String description) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("description", description);
        return JSON.writeValueAsString(body);
    }

    @Test
    void eachListCanBeListedAddedAndEdited() throws Exception {
        Cookie admin = login(ADMIN);
        for (String list : new String[] {"purposes", "complaint-types", "sources", "references"}) {
            var created = mockMvc.perform(post("/api/v1/front-office-setup/" + list).cookie(admin)
                            .contentType(MediaType.APPLICATION_JSON).content(item("  New " + list + "  ", "Added in setup")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("New " + list))
                    .andExpect(jsonPath("$.description").value("Added in setup"))
                    .andReturn();
            String id = JSON.readTree(created.getResponse().getContentAsString()).get("id").asText();
            mockMvc.perform(put("/api/v1/front-office-setup/" + list + "/" + id).cookie(admin)
                            .contentType(MediaType.APPLICATION_JSON).content(item("Renamed " + list, null)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Renamed " + list))
                    .andExpect(jsonPath("$.description").doesNotExist());
            mockMvc.perform(get("/api/v1/front-office-setup/" + list).cookie(admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.name == 'Renamed " + list + "')]").exists());
        }
    }

    @Test
    void namesMustBeUniqueIgnoringCaseAndAreRequired() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(post("/api/v1/front-office-setup/purposes").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(item("school events", null)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/front-office-setup/purposes").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(item(" ", null)))
                .andExpect(status().isBadRequest());
        String marketingId = JSON.readTree(mockMvc.perform(get("/api/v1/front-office-setup/purposes").cookie(admin))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asText(); // "Marketing" sorts first
        mockMvc.perform(put("/api/v1/front-office-setup/purposes/" + marketingId).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(item("SCHOOL EVENTS", null)))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/front-office-setup/nonsense").cookie(admin)).andExpect(status().isNotFound());
    }

    @Test
    void anUnusedEntryIsDeleted() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(delete("/api/v1/front-office-setup/references/" + staffId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"));
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            var rs = st.executeQuery("SELECT count(*) FROM enquiry_references WHERE id = '" + staffId + "'");
            rs.next();
            org.assertj.core.api.Assertions.assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void anEntryInUseIsHiddenNotDeletedAndCanBeRestoredByAddingItAgain() throws Exception {
        // A visitor was logged under "School Events".
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            UUID schoolId = UUID.randomUUID();
            UUID studentId = UUID.randomUUID();
            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Setup School') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, status) "
                    + "VALUES ('" + studentId + "', '" + schoolId + "', 'Edward Thomas', 'Edward', 'Thomas', 'FS-1', 'ACTIVE')");
            st.execute("INSERT INTO visitors (purpose_id, meeting_with_type, student_id, visitor_name, visit_date) VALUES ('"
                    + schoolEventsId + "', 'STUDENT', '" + studentId + "', 'Jhon', '2026-09-26')");
        }
        Cookie admin = login(ADMIN);

        mockMvc.perform(delete("/api/v1/front-office-setup/purposes/" + schoolEventsId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DEACTIVATED"))
                .andExpect(jsonPath("$.usageCount").value(1));
        // Gone from the setup list and from the Visitor Book form's dropdown...
        mockMvc.perform(get("/api/v1/front-office-setup/purposes").cookie(admin))
                .andExpect(jsonPath("$[?(@.name == 'School Events')]").doesNotExist());
        // ...but the row (and so the visitor's purpose) is kept.
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            var rs = st.executeQuery("SELECT name, active FROM front_office_purposes WHERE id = '" + schoolEventsId + "'");
            rs.next();
            org.assertj.core.api.Assertions.assertThat(rs.getString(1)).isEqualTo("School Events");
            org.assertj.core.api.Assertions.assertThat(rs.getBoolean(2)).isFalse();
        }
        // Another entry can't take the hidden entry's name...
        String marketingId = JSON.readTree(mockMvc.perform(get("/api/v1/front-office-setup/purposes").cookie(admin))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asText();
        mockMvc.perform(put("/api/v1/front-office-setup/purposes/" + marketingId).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(item("School Events", null)))
                .andExpect(status().isConflict());
        // ...but adding it again restores the original entry, same id.
        mockMvc.perform(post("/api/v1/front-office-setup/purposes").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(item("School Events", "Annual day, sports day")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(schoolEventsId.toString()))
                .andExpect(jsonPath("$.description").value("Annual day, sports day"));
    }

    @Test
    void onlyAdminsCanManageTheLists() throws Exception {
        Cookie receptionist = login(RECEPTIONIST);
        mockMvc.perform(get("/api/v1/front-office-setup/purposes").cookie(receptionist)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/front-office-setup/purposes").cookie(receptionist)
                        .contentType(MediaType.APPLICATION_JSON).content(item("Sneaky", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/front-office-setup/references/" + staffId).cookie(receptionist))
                .andExpect(status().isForbidden());
    }
}
