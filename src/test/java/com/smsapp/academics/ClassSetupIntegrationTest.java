package com.smsapp.academics;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Academics > Sections (master list) and Class (a class with its sections) against a real PostgreSQL instance. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassSetupIntegrationTest {

    private static final String ADMIN = "cs-admin@school.example";
    private static final String TEACHER = "cs-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie admin;
    private String sectionA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, classes, sections, section_names, subjects, subject_groups, "
                    + "timetable_entries, class_subjects, students CASCADE");
            st.execute("INSERT INTO schools (id, name) VALUES (gen_random_uuid(), 'Test School')");
            seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, TEACHER, "TEACHER");
        }
        admin = login(ADMIN);
        sectionA = idOf(postJson("/api/v1/academics/section-names", Map.of("name", "A")));
        postJson("/api/v1/academics/section-names", Map.of("name", "B")).andExpect(status().isCreated());
        postJson("/api/v1/academics/section-names", Map.of("name", "C")).andExpect(status().isCreated());
    }

    private void seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url).cookie(admin).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mockMvc.perform(put(url).cookie(admin).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private String idOf(ResultActions result) throws Exception {
        return JSON.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String sectionIdOf(String classId, String name) throws Exception {
        JsonNode classes = JSON.readTree(mockMvc.perform(get("/api/v1/classes").cookie(admin)).andReturn().getResponse().getContentAsString());
        for (JsonNode c : classes) {
            if (classId.equals(c.get("id").asText())) {
                for (JsonNode s : c.get("sections")) {
                    if (name.equals(s.get("name").asText())) {
                        return s.get("id").asText();
                    }
                }
            }
        }
        throw new AssertionError("section " + name + " not found");
    }

    @Test
    void sectionNamesAreUniqueAndFollowTheirClasses() throws Exception {
        postJson("/api/v1/academics/section-names", Map.of("name", "a")).andExpect(status().isConflict());
        postJson("/api/v1/academics/section-names", Map.of("name", " ")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/academics/section-names").cookie(admin)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("A"));

        String classId = idOf(postJson("/api/v1/academics/classes", Map.of("name", "Class 1", "sectionNames", List.of("A", "B"))));
        // Renaming an entry renames the section of every class that has it; a used one can't be deleted.
        putJson("/api/v1/academics/section-names/" + sectionA, Map.of("name", "B")).andExpect(status().isConflict());
        putJson("/api/v1/academics/section-names/" + sectionA, Map.of("name", "Alpha")).andExpect(status().isOk());
        sectionIdOf(classId, "Alpha");
        mockMvc.perform(delete("/api/v1/academics/section-names/" + sectionA).cookie(admin)).andExpect(status().isConflict());
        String unused = JSON.readTree(mockMvc.perform(get("/api/v1/academics/section-names").cookie(admin)).andReturn().getResponse().getContentAsString())
                .get(2).get("id").asText();
        mockMvc.perform(delete("/api/v1/academics/section-names/" + unused).cookie(admin)).andExpect(status().isNoContent());
    }

    @Test
    void aClassIsCreatedWithItsSectionsFromTheList() throws Exception {
        postJson("/api/v1/academics/classes", Map.of("name", "Class 1", "sectionNames", List.of())).andExpect(status().isBadRequest());
        postJson("/api/v1/academics/classes", Map.of("name", "Class 1", "sectionNames", List.of("Z"))).andExpect(status().isNotFound());
        postJson("/api/v1/academics/classes", Map.of("name", "Class 1", "sectionNames", List.of("a", "B", "b")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Class 1"))
                .andExpect(jsonPath("$.sections.length()").value(2));
        postJson("/api/v1/academics/classes", Map.of("name", "Class 1", "sectionNames", List.of("A"))).andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/classes").cookie(admin)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void editingSyncsTheClassAndItsSections() throws Exception {
        String classId = idOf(postJson("/api/v1/academics/classes", Map.of("name", "Class 1", "sectionNames", List.of("A", "B"))));
        putJson("/api/v1/academics/classes/" + classId, Map.of("name", "Grade 1", "sectionNames", List.of("B", "C")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Grade 1")).andExpect(jsonPath("$.sections.length()").value(2));
        sectionIdOf(classId, "C");
        String other = idOf(postJson("/api/v1/academics/classes", Map.of("name", "Class 2", "sectionNames", List.of("A"))));
        putJson("/api/v1/academics/classes/" + other, Map.of("name", "Grade 1", "sectionNames", List.of("A"))).andExpect(status().isConflict());
        putJson("/api/v1/academics/classes/" + UUID.randomUUID(), Map.of("name", "X", "sectionNames", List.of("A"))).andExpect(status().isNotFound());
    }

    @Test
    void sectionsInUseCantBeTakenOffAndClassesInUseCantBeDeleted() throws Exception {
        String classId = idOf(postJson("/api/v1/academics/classes", Map.of("name", "Class 1", "sectionNames", List.of("A", "B"))));
        String sectionB = sectionIdOf(classId, "B");
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("INSERT INTO students (id, school_id, section_id, full_name, first_name, last_name, admission_number, status) "
                    + "SELECT gen_random_uuid(), school_id, '" + sectionB + "', 'S', 'S', 'B', 'ADM-1', 'ACTIVE' FROM classes LIMIT 1");
        }
        putJson("/api/v1/academics/classes/" + classId, Map.of("name", "Class 1", "sectionNames", List.of("A"))).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/academics/classes/" + classId).cookie(admin)).andExpect(status().isConflict());
        // An empty class can be deleted.
        String empty = idOf(postJson("/api/v1/academics/classes", Map.of("name", "Class 9", "sectionNames", List.of("C"))));
        mockMvc.perform(delete("/api/v1/academics/classes/" + empty).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/classes").cookie(admin)).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(delete("/api/v1/academics/classes/" + empty).cookie(admin)).andExpect(status().isNotFound());
    }

    @Test
    void onlySchoolAdminsCanUseThem() throws Exception {
        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/academics/section-names").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/academics/classes").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"sectionNames\":[\"A\"]}")).andExpect(status().isForbidden());
    }
}
