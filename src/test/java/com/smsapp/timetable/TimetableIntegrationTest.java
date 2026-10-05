package com.smsapp.timetable;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Academics > Subjects, Subject Group and Class Timetable against a real PostgreSQL instance. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TimetableIntegrationTest {

    private static final String ADMIN = "tt-admin@school.example";
    private static final String TEACHER = "tt-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie admin;
    private UUID classId;
    private UUID sectionA;
    private UUID sectionB;
    private UUID staffRoleId;
    private String shivam;
    private String jason;
    private String english;
    private String maths;
    private String groupId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws Exception {
        UUID schoolId = UUID.randomUUID();
        classId = UUID.randomUUID();
        sectionA = UUID.randomUUID();
        sectionB = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, staff_profiles, classes, sections, subjects, "
                    + "subject_groups, timetable_entries, class_subjects, departments, designations CASCADE");
            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Test School')");
            st.execute("INSERT INTO classes (id, school_id, name) VALUES ('" + classId + "', '" + schoolId + "', 'Class 1')");
            st.execute("INSERT INTO sections (id, class_id, name) VALUES ('" + sectionA + "', '" + classId + "', 'A'), ('" + sectionB + "', '" + classId + "', 'B')");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, TEACHER, "TEACHER");
            staffRoleId = UUID.randomUUID();
            st.execute("INSERT INTO roles (id, name) VALUES ('" + staffRoleId + "', 'STAFF_TEACHER')");
            for (String name : new String[] {"TIMETABLE_VIEW", "TIMETABLE_MANAGE", "SUBJECT_MANAGE", "STAFF_VIEW", "STAFF_CREATE"}) {
                st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + adminRole + "', id FROM permissions WHERE name = '" + name + "'");
            }
        }
        admin = login(ADMIN);
        shivam = addStaff("9002", "Shivam");
        jason = addStaff("90006", "Jason");
        english = idOf(postJson("/api/v1/academics/subjects", Map.of("name", "English", "code", "210", "type", "Theory")));
        maths = idOf(postJson("/api/v1/academics/subjects", Map.of("name", "Mathematics", "code", "110", "type", "Theory")));
        var group = new LinkedHashMap<String, Object>();
        group.put("name", "Class 1 subject");
        group.put("classId", classId);
        group.put("sectionIds", List.of(sectionA, sectionB));
        group.put("subjectIds", List.of(english, maths));
        groupId = idOf(postJson("/api/v1/academics/subject-groups", group));
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

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url).cookie(admin).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mockMvc.perform(put(url).cookie(admin).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private String idOf(ResultActions result) throws Exception {
        return JSON.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private Map<String, Object> period(int day, String subject, String from, String to, String teacher, String room) {
        var p = new LinkedHashMap<String, Object>();
        p.put("dayOfWeek", day);
        p.put("subjectId", subject);
        p.put("timeFrom", from);
        p.put("timeTo", to);
        p.put("staffProfileId", teacher);
        p.put("roomNo", room);
        return p;
    }

    private ResultActions save(UUID section, List<Map<String, Object>> periods) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("sectionId", section);
        body.put("subjectGroupId", groupId);
        body.put("periods", periods);
        return putJson("/api/v1/academics/timetable", body);
    }

    @Test
    void subjectsHaveUniqueNamesAndCodesAndCanBeEdited() throws Exception {
        postJson("/api/v1/academics/subjects", Map.of("name", "English", "type", "Theory")).andExpect(status().isConflict());
        postJson("/api/v1/academics/subjects", Map.of("name", "Hindi", "code", "210", "type", "Theory")).andExpect(status().isConflict());
        postJson("/api/v1/academics/subjects", Map.of("name", "Hindi", "type", "Other")).andExpect(status().isBadRequest());
        String physics = idOf(postJson("/api/v1/academics/subjects", Map.of("name", "Physics Lab", "code", "311", "type", "practical")));
        mockMvc.perform(get("/api/v1/academics/subjects").cookie(admin)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].name").value("Mathematics")).andExpect(jsonPath("$[2].type").value("PRACTICAL"));
        putJson("/api/v1/academics/subjects/" + physics, Map.of("name", "Physics Lab", "code", "312", "type", "Practical")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("312"));
        putJson("/api/v1/academics/subjects/" + physics, Map.of("name", "Physics Lab", "code", "210", "type", "Practical")).andExpect(status().isConflict());
        // In a subject group -> can't be deleted; unused -> can.
        mockMvc.perform(delete("/api/v1/academics/subjects/" + english).cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/academics/subjects/" + physics).cookie(admin)).andExpect(status().isNoContent());
    }

    @Test
    void subjectGroupsValidateTheirClassSectionsAndSubjects() throws Exception {
        var group = new LinkedHashMap<String, Object>();
        group.put("name", "class 1 SUBJECT");
        group.put("classId", classId);
        group.put("sectionIds", List.of(sectionA));
        group.put("subjectIds", List.of(english));
        postJson("/api/v1/academics/subject-groups", group).andExpect(status().isConflict());
        group.put("name", "Another");
        group.put("sectionIds", List.of(UUID.randomUUID()));
        postJson("/api/v1/academics/subject-groups", group).andExpect(status().isNotFound());
        group.put("sectionIds", List.of());
        postJson("/api/v1/academics/subject-groups", group).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/academics/subject-groups").cookie(admin).param("sectionId", sectionA.toString()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].className").value("Class 1"))
                .andExpect(jsonPath("$[0].sections.length()").value(2)).andExpect(jsonPath("$[0].subjects[0].name").value("English"));
    }

    @Test
    void savingReplacesTheGroupsPeriodsAndListsTheWeek() throws Exception {
        save(sectionA, List.of(period(1, english, "08:00", "08:45", shivam, "100"), period(1, maths, "08:45", "09:30", jason, "100")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].subjectName").value("English")).andExpect(jsonPath("$[0].staffName").value("Shivam"))
                .andExpect(jsonPath("$[0].staffCode").value("9002")).andExpect(jsonPath("$[0].timeFrom").value("08:00:00"));
        mockMvc.perform(get("/api/v1/academics/timetable").cookie(admin).param("sectionId", sectionA.toString()))
                .andExpect(jsonPath("$.length()").value(2));
        // Saving again replaces (and an empty list clears the group's periods).
        save(sectionA, List.of(period(2, maths, "09:00", "09:40", null, null))).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/academics/timetable").cookie(admin).param("sectionId", sectionA.toString()).param("subjectGroupId", groupId))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].dayOfWeek").value(2));
        save(sectionA, List.of()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/academics/timetable").cookie(admin).param("sectionId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test
    void overlapsAndBadPeriodsAreRejected() throws Exception {
        save(sectionA, List.of(period(1, english, "08:00", "08:45", null, null), period(1, maths, "08:30", "09:15", null, null)))
                .andExpect(status().isConflict());
        save(sectionA, List.of(period(1, english, "09:00", "08:00", null, null))).andExpect(status().isBadRequest());
        save(sectionA, List.of(period(8, english, "08:00", "09:00", null, null))).andExpect(status().isBadRequest());
        // Back-to-back periods are fine.
        save(sectionA, List.of(period(1, english, "08:00", "08:45", null, null), period(1, maths, "08:45", "09:30", null, null)))
                .andExpect(status().isOk());
        // A subject outside the group is refused.
        String hindi = idOf(postJson("/api/v1/academics/subjects", Map.of("name", "Hindi", "type", "Theory")));
        save(sectionA, List.of(period(1, hindi, "10:00", "10:45", null, null))).andExpect(status().isBadRequest());
    }

    @Test
    void aTeacherCannotTeachTwoSectionsAtOnce() throws Exception {
        save(sectionA, List.of(period(1, english, "08:00", "08:45", shivam, "100"))).andExpect(status().isOk());
        save(sectionB, List.of(period(1, english, "08:30", "09:15", shivam, "101"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Shivam is already teaching")));
        save(sectionB, List.of(period(1, english, "08:45", "09:30", shivam, "101"))).andExpect(status().isOk());
        // Re-saving a section's own periods doesn't clash with itself.
        save(sectionA, List.of(period(1, english, "08:00", "08:45", shivam, "100"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/academics/timetable/teacher/" + shivam).cookie(admin)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void groupsWithPeriodsAreProtected() throws Exception {
        save(sectionA, List.of(period(1, english, "08:00", "08:45", null, null))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/academics/subject-groups/" + groupId).cookie(admin)).andExpect(status().isConflict());
        var group = new LinkedHashMap<String, Object>();
        group.put("name", "Class 1 subject");
        group.put("classId", classId);
        group.put("sectionIds", List.of(sectionA));
        group.put("subjectIds", List.of(maths));
        putJson("/api/v1/academics/subject-groups/" + groupId, group).andExpect(status().isConflict());
        group.put("subjectIds", List.of(english, maths));
        putJson("/api/v1/academics/subject-groups/" + groupId, group).andExpect(status().isOk()).andExpect(jsonPath("$.sections.length()").value(1));
        save(sectionA, List.of()).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/academics/subject-groups/" + groupId).cookie(admin)).andExpect(status().isNoContent());
    }

    @Test
    void permissionsGateTheEndpoints() throws Exception {
        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/academics/timetable").cookie(teacher).param("sectionId", sectionA.toString())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/academics/subjects").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/academics/timetable").cookie(teacher).contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\",\"subjectGroupId\":\"" + groupId + "\",\"periods\":[]}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/academics/subjects/" + english).cookie(teacher)).andExpect(status().isForbidden());
    }
}
