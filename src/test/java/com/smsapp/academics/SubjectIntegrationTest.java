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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Subjects + class-subject assignment against a real PostgreSQL instance with RLS:
 * tenant isolation, SCHOOL_ADMIN-only writes, clean 409 on duplicate name /
 * assignment, and cross-tenant assignment fails with 404 (plan section 2 / 7c-d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SubjectIntegrationTest {

    private static final String SCHOOL_A = "subj-school-a";
    private static final String SCHOOL_B = "subj-school-b";
    private static final String ADMIN_A = "admin@tenant-a.example";
    private static final String TEACHER_A = "teacher@tenant-a.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;
    private UUID classB;
    private UUID subjectB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        schoolA = UUID.randomUUID();
        UUID schoolB = UUID.randomUUID();
        classB = UUID.randomUUID();
        subjectB = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, class_subjects, subjects, sections, classes CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolA);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolB);

            seedUser(st, tenantA, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, tenantA, TEACHER_A, "TEACHER");
            seedUser(st, tenantB, "admin@tenant-b.example", "SCHOOL_ADMIN");

            // Tenant B already owns a class + a subject -- tenant A must never see or use them.
            st.execute("INSERT INTO classes (id, tenant_id, school_id, name) VALUES ('"
                    + classB + "', '" + tenantB + "', '" + schoolB + "', 'Grade 1')");
            st.execute("INSERT INTO subjects (id, tenant_id, school_id, name) VALUES ('"
                    + subjectB + "', '" + tenantB + "', '" + schoolB + "', 'History')");
        }
    }

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, UUID schoolId)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
    }

    private void seedUser(Statement st, UUID tenantId, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '"
                + email + "')");
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID id(String responseBody) throws Exception {
        return UUID.fromString(JSON.readTree(responseBody).get("id").asText());
    }

    private UUID createSubjectA(Cookie admin, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/subjects").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return id(result.getResponse().getContentAsString());
    }

    private UUID createClassA(Cookie admin, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/classes").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return id(result.getResponse().getContentAsString());
    }

    // --- Subject creation --------------------------------------------

    @Test
    void schoolAdminCanCreateASubjectButTeacherCannot() throws Exception {
        mockMvc.perform(post("/api/v1/subjects").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Mathematics\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Mathematics"))
                .andExpect(jsonPath("$.schoolId").value(schoolA.toString()));

        mockMvc.perform(post("/api/v1/subjects").cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Science\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateSubjectNameReturns409() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        createSubjectA(admin, "Mathematics");

        var result = mockMvc.perform(post("/api/v1/subjects").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Mathematics\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Mathematics")))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("sql", "constraint", "exception");
    }

    @Test
    void listSubjectsIsTenantScoped() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        createSubjectA(admin, "Mathematics");

        // Tenant B has a "History" subject seeded -- it must not appear for tenant A.
        mockMvc.perform(get("/api/v1/subjects").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Mathematics"));
    }

    // --- Class-subject assignment -----------------------------------

    @Test
    void schoolAdminCanAssignASubjectToAClassButTeacherCannot() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        UUID subjectA = createSubjectA(admin, "Mathematics");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(subjectA.toString()))
                .andExpect(jsonPath("$.name").value("Mathematics"));

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assigningTheSameSubjectTwiceReturns409() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        UUID subjectA = createSubjectA(admin, "Mathematics");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotAssignAnotherTenantsSubjectToOwnClass() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectB + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotAssignOwnSubjectToAnotherTenantsClass() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID subjectA = createSubjectA(admin, "Mathematics");

        mockMvc.perform(post("/api/v1/classes/" + classB + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectA + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listClassSubjectsReturnsAssignedSubjectsTenantScoped() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        UUID math = createSubjectA(admin, "Mathematics");
        createSubjectA(admin, "Science"); // exists but not assigned

        mockMvc.perform(post("/api/v1/classes/" + classA + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + math + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/classes/" + classA + "/subjects").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Mathematics"));

        // A tenant-A caller cannot read a tenant-B class's subjects.
        mockMvc.perform(get("/api/v1/classes/" + classB + "/subjects").cookie(admin))
                .andExpect(status().isNotFound());
    }
}
