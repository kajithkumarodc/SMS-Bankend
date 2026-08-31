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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Classes / sections module against a real PostgreSQL instance with RLS enabled:
 * tenant isolation, SCHOOL_ADMIN-only writes, and cross-tenant section assignment
 * fails with 404 (plan section 2 / 7c-d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassSectionIntegrationTest {

    private static final String SCHOOL_A = "cls-school-a";
    private static final String SCHOOL_B = "cls-school-b";
    private static final String ADMIN_A = "admin@tenant-a.example";
    private static final String TEACHER_A = "teacher@tenant-a.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;
    private UUID studentA;
    private UUID classB;
    private UUID sectionB;

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
        studentA = UUID.randomUUID();
        classB = UUID.randomUUID();
        sectionB = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolA);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolB);

            seedUser(st, tenantA, ADMIN_A, "SCHOOL_ADMIN");
            seedUser(st, tenantA, TEACHER_A, "TEACHER");
            seedUser(st, tenantB, "admin@tenant-b.example", "SCHOOL_ADMIN");

            st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status) VALUES ('"
                    + studentA + "', '" + tenantA + "', '" + schoolA + "', 'Student A', 'ADM-A', 'ACTIVE')");

            // Tenant B already owns a class + section -- tenant A must never see or use them.
            st.execute("INSERT INTO classes (id, tenant_id, school_id, name) VALUES ('"
                    + classB + "', '" + tenantB + "', '" + schoolB + "', 'Grade 1')");
            st.execute("INSERT INTO sections (id, tenant_id, class_id, name) VALUES ('"
                    + sectionB + "', '" + tenantB + "', '" + classB + "', 'X')");
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

    /** Creates a class for tenant A via the API and returns its id. */
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
                        .cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Grade 5\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Grade 5"))
                .andExpect(jsonPath("$.sections.length()").value(0));
    }

    @Test
    void teacherCannotCreateAClass() throws Exception {
        mockMvc.perform(post("/api/v1/classes")
                        .cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Grade 5\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void schoolAdminCanCreateASectionButTeacherCannot() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");

        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("A"))
                .andExpect(jsonPath("$.classId").value(classA.toString()));

        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections")
                        .cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"B\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Tenant isolation --------------------------------------------

    @Test
    void listClassesNestsSectionsAndIsTenantScoped() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID classA = createClassA(admin, "Grade 5");
        mockMvc.perform(post("/api/v1/classes/" + classA + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated());

        // Tenant B has a class "Grade 1" seeded -- it must not appear for tenant A.
        mockMvc.perform(get("/api/v1/classes").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Grade 5"))
                .andExpect(jsonPath("$[0].sections.length()").value(1))
                .andExpect(jsonPath("$[0].sections[0].name").value("A"));
    }

    @Test
    void cannotCreateSectionUnderAnotherTenantsClass() throws Exception {
        mockMvc.perform(post("/api/v1/classes/" + classB + "/sections")
                        .cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Y\"}"))
                .andExpect(status().isNotFound());
    }

    // --- Student <-> section assignment -----------------------------

    private UUID createSectionA() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
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
                        .cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionId").value(sectionA.toString()));
    }

    @Test
    void teacherCannotAssignAStudentToASection() throws Exception {
        UUID sectionA = createSectionA();

        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section")
                        .cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assigningAStudentToAnotherTenantsSectionReturns404() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section")
                        .cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionB + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentsCanBeFilteredBySection() throws Exception {
        UUID sectionA = createSectionA();
        Cookie admin = login(SCHOOL_A, ADMIN_A);
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
}
