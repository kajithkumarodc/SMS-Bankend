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

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Classes / sections module against a real PostgreSQL instance: SCHOOL_ADMIN-only
 * writes, and a nonexistent class/section reference fails with 404 (plan
 * section 2 / 7c-d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassSectionIntegrationTest {

    private static final String ADMIN_A = "admin@tenant-a.example";
    private static final String TEACHER_A = "teacher@tenant-a.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;
    private UUID studentA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolA = UUID.randomUUID();
        studentA = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Tenant A School')");

            seedUser(st, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, TEACHER_A, "TEACHER");

            st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, status) VALUES ('"
                    + studentA + "', '" + schoolA + "', 'Student A', 'Student', 'A', 'ADM-A', 'ACTIVE')");
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

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    /** Creates a class via the API and returns its id. */
    private UUID createClassA(Cookie admin, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/classes").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Class / section writes are SCHOOL_ADMIN only ------------------

    @Test
    void schoolAdminCanCreateAClass() throws Exception {
        mockMvc.perform(post("/api/v1/classes")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Grade 5\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Grade 5"))
                .andExpect(jsonPath("$.sections.length()").value(0));
    }

    @Test
    void teacherCannotCreateAClass() throws Exception {
        mockMvc.perform(post("/api/v1/classes")
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Grade 5\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void schoolAdminCanCreateASectionButTeacherCannot() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("A"))
                .andExpect(jsonPath("$.classId").value(classA.toString()));

        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections")
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"B\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Listing / existence checks --------------------------------------

    @Test
    void listClassesNestsSections() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Grade 5"))
                .andExpect(jsonPath("$[0].sections.length()").value(1))
                .andExpect(jsonPath("$[0].sections[0].name").value("A"));
    }

    @Test
    void cannotCreateSectionUnderANonexistentClass() throws Exception {
        mockMvc.perform(post("/api/v1/classes/" + UUID.randomUUID() + "/sections")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Y\"}"))
                .andExpect(status().isNotFound());
    }

    // --- Student <-> section assignment -----------------------------

    private UUID createSectionA() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        var result = mockMvc.perform(post("/api/v1/classes/" + classA + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void schoolAdminCanAssignAStudentToASection() throws Exception {
        UUID sectionA = createSectionA();

        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionId").value(sectionA.toString()));
    }

    @Test
    void teacherCannotAssignAStudentToASection() throws Exception {
        UUID sectionA = createSectionA();

        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section")
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assigningAStudentToANonexistentSectionReturns404() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentsCanBeFilteredBySection() throws Exception {
        UUID sectionA = createSectionA();
        Cookie admin = login(ADMIN_A);
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/students").param("sectionId", sectionA.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(studentA.toString()));

        mockMvc.perform(get("/api/v1/students").param("sectionId", UUID.randomUUID().toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // --- Sections are flexible: rename and delete without touching the class ---------

    @Test
    void aSectionCanBeRenamedButNotToANameTheClassAlreadyHas() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classId = createClassA(admin, "Class 5");
        UUID sectionA = createSection(admin, classId, "A");
        createSection(admin, classId, "B");

        mockMvc.perform(put("/api/v1/classes/" + classId + "/sections/" + sectionA).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" Rose \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rose"))
                .andExpect(jsonPath("$.classId").value(classId.toString()));
        mockMvc.perform(put("/api/v1/classes/" + classId + "/sections/" + sectionA).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"B\"}"))
                .andExpect(status().isConflict());
        // A section id under the wrong class is a 404.
        UUID otherClass = createClassA(admin, "Class 6");
        mockMvc.perform(put("/api/v1/classes/" + otherClass + "/sections/" + sectionA).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"C\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/classes/" + classId + "/sections/" + sectionA).cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"C\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmptySectionCanBeDeletedButOneWithStudentsCannot() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classId = createClassA(admin, "Class 5");
        UUID sectionA = createSection(admin, classId, "A");
        UUID sectionB = createSection(admin, classId, "B");
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/classes/" + classId + "/sections/" + sectionA).cookie(admin))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/classes/" + classId + "/sections/" + sectionB).cookie(admin))
                .andExpect(status().isNoContent());

        // The class and its remaining section are untouched.
        mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(jsonPath("$[?(@.name == 'Class 5')].sections.length()").value(1))
                .andExpect(jsonPath("$[?(@.name == 'Class 5')].sections[0].name").value("A"));
        mockMvc.perform(delete("/api/v1/classes/" + classId + "/sections/" + sectionB).cookie(admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/classes/" + classId + "/sections/" + sectionA).cookie(login(TEACHER_A)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aClassWithoutSectionsTakesStudentsThroughItsDefaultSection() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID lkg = createClassA(admin, "LKG");

        // A new class has one hidden default section that students can be placed in directly.
        var listed = JSON.readTree(mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var sections = listed.get(0).get("sections");
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).get("isDefault").asBoolean()).isTrue();
        UUID defaultSection = UUID.fromString(sections.get(0).get("id").asText());
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + defaultSection + "\"}"))
                .andExpect(status().isOk());

        // The default section can't be renamed or deleted directly.
        mockMvc.perform(put("/api/v1/classes/" + lkg + "/sections/" + defaultSection).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/classes/" + lkg + "/sections/" + defaultSection).cookie(admin))
                .andExpect(status().isNotFound());

        // The first real section takes it over in place: the student is now in section A.
        UUID sectionA = createSection(admin, lkg, "A");
        assertThat(sectionA).isEqualTo(defaultSection);
        mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(jsonPath("$[0].sections.length()").value(1))
                .andExpect(jsonPath("$[0].sections[0].name").value("A"))
                .andExpect(jsonPath("$[0].sections[0].isDefault").value(false));
        mockMvc.perform(get("/api/v1/students").param("sectionId", sectionA.toString()).cookie(admin))
                .andExpect(jsonPath("$.content[0].id").value(studentA.toString()));

        // Deleting the last (empty) real section turns it back into the default section.
        UUID sectionB = createSection(admin, lkg, "B");
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionB + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/classes/" + lkg + "/sections/" + sectionA).cookie(admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(jsonPath("$[0].sections.length()").value(1))
                .andExpect(jsonPath("$[0].sections[0].name").value("B"));
    }

    @Test
    void deletingTheLastSectionLeavesTheClassWithADefaultSection() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID classId = createClassA(admin, "UKG");
        UUID sectionA = createSection(admin, classId, "A");
        mockMvc.perform(delete("/api/v1/classes/" + classId + "/sections/" + sectionA).cookie(admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(jsonPath("$[0].sections.length()").value(1))
                .andExpect(jsonPath("$[0].sections[0].isDefault").value(true));
    }

    @Test
    void classesAreListedInSchoolOrder() throws Exception {
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("INSERT INTO classes (id, school_id, name, sort_order) VALUES "
                    + "(gen_random_uuid(), '" + schoolA + "', 'Class 10', 11), "
                    + "(gen_random_uuid(), '" + schoolA + "', 'Class 2', 3), "
                    + "(gen_random_uuid(), '" + schoolA + "', 'UKG', 1), "
                    + "(gen_random_uuid(), '" + schoolA + "', 'LKG', 0)");
        }
        Cookie admin = login(ADMIN_A);
        createClassA(admin, "Arts Club"); // added later: listed after the standard classes
        mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains("LKG", "UKG", "Class 2", "Class 10", "Arts Club")));
    }

    private UUID createSection(Cookie admin, UUID classId, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/classes/" + classId + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
}
