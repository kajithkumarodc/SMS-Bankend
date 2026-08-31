package com.smsapp.student;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves, against a real PostgreSQL instance with RLS enabled, that the new
 * {@code students} table is tenant-isolated (plan section 7c) and that only
 * SCHOOL_ADMIN can create a student (plan section 7d).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentSisIntegrationTest {

    private static final String SCHOOL_A = "sis-school-a";
    private static final String SCHOOL_B = "sis-school-b";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID tenantA;
    private UUID tenantB;
    private UUID schoolA;
    private UUID schoolB;
    private UUID studentB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        schoolA = UUID.randomUUID();
        schoolB = UUID.randomUUID();
        studentB = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolA);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolB);

            // Tenant A users: one SCHOOL_ADMIN, one TEACHER.
            seedUser(st, tenantA, "admin@tenant-a.example", "secret", "Admin A", "SCHOOL_ADMIN");
            seedUser(st, tenantA, "teacher@tenant-a.example", "secret", "Teacher A", "TEACHER");
            // Tenant B user: a SCHOOL_ADMIN (unused for auth here, kept for realism).
            seedUser(st, tenantB, "admin@tenant-b.example", "secret", "Admin B", "SCHOOL_ADMIN");

            // One pre-existing student in each tenant. Tenant A uses "ADM-A-1";
            // tenant B uses "ADM-100" -- tenant A can then reuse "ADM-100" because
            // uniqueness is per-tenant.
            st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status) VALUES ('"
                    + UUID.randomUUID() + "', '" + tenantA + "', '" + schoolA + "', 'Existing A', 'ADM-A-1', 'ACTIVE')");
            st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status) VALUES ('"
                    + studentB + "', '" + tenantB + "', '" + schoolB + "', 'Existing B', 'ADM-100', 'ACTIVE')");
        }
    }

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, UUID schoolId)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
    }

    private void seedUser(Statement st, UUID tenantId, String email, String password, String fullName, String role)
            throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode(password) + "', '"
                + fullName + "')");
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String createBody(UUID schoolId, String admissionNumber) {
        return "{\"schoolId\":\"" + schoolId + "\",\"fullName\":\"New Student\",\"admissionNumber\":\""
                + admissionNumber + "\"}";
    }

    // --- Tenant isolation --------------------------------------------------

    @Test
    void listReturnsOnlyCallersTenantStudents() throws Exception {
        var response = mockMvc.perform(get("/api/v1/students").cookie(login(SCHOOL_A, "admin@tenant-a.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("Existing A"))
                .andReturn();

        JsonNode content = JSON.readTree(response.getResponse().getContentAsString()).get("content");
        assertThat(content).allMatch(node -> node.get("fullName").asText().equals("Existing A"));
    }

    @Test
    void getByIdOfAnotherTenantsStudentReturns404NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/students/" + studentB).cookie(login(SCHOOL_A, "admin@tenant-a.example")))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotCreateStudentAgainstAnotherTenantsSchool() throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(SCHOOL_A, "admin@tenant-a.example"))
                        .contentType("application/json")
                        .content(createBody(schoolB, "ADM-A-1")))
                .andExpect(status().isNotFound());

        // And nothing leaked into tenant B.
        var listB = mockMvc.perform(get("/api/v1/students").cookie(login(SCHOOL_B, "admin@tenant-b.example")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(JSON.readTree(listB.getResponse().getContentAsString()).get("content")).hasSize(1);
    }

    @Test
    void admissionNumberUniquenessIsPerTenantNotGlobal() throws Exception {
        // "ADM-100" already exists in tenant B, but not for tenant A -> allowed.
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(SCHOOL_A, "admin@tenant-a.example"))
                        .contentType("application/json")
                        .content(createBody(schoolA, "ADM-100")))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateAdmissionNumberWithinTenantReturns409WithClearMessage() throws Exception {
        var result = mockMvc.perform(post("/api/v1/students")
                        .cookie(login(SCHOOL_A, "admin@tenant-a.example"))
                        .contentType("application/json")
                        .content(createBody(schoolA, "ADM-A-1"))) // "Existing A" already uses this
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ADM-A-1")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("sql", "constraint", "exception");
    }

    // --- Role-based authorization ----------------------------------------

    @Test
    void schoolAdminCanCreateStudent() throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(SCHOOL_A, "admin@tenant-a.example"))
                        .contentType("application/json")
                        .content(createBody(schoolA, "ADM-NEW-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void teacherCannotCreateStudentAndGets403() throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(SCHOOL_A, "teacher@tenant-a.example"))
                        .contentType("application/json")
                        .content(createBody(schoolA, "ADM-NEW-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void teacherCanStillListStudents() throws Exception {
        mockMvc.perform(get("/api/v1/students").cookie(login(SCHOOL_A, "teacher@tenant-a.example")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void validationRejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .cookie(login(SCHOOL_A, "admin@tenant-a.example"))
                        .contentType("application/json")
                        .content("{\"schoolId\":\"" + schoolA + "\",\"fullName\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
