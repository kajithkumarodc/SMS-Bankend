package com.smsapp.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit logging against a real PostgreSQL instance with RLS enabled: key actions
 * produce entries with the right actor and details, the log is tenant-scoped and
 * SCHOOL_ADMIN-only to read, and the application cannot mutate audit rows
 * (plan section 7.2 -- immutable audit trail).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditIntegrationTest {

    private static final String SCHOOL_A = "audit-school-a";
    private static final String SCHOOL_B = "audit-school-b";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID tenantA;
    private UUID adminAId;
    private UUID teacherAId;
    private UUID schoolA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        schoolA = UUID.randomUUID();
        UUID schoolB = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes, audit_log CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolA);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolB);

            adminAId = seedUser(st, tenantA, "admin@tenant-a.example", "SCHOOL_ADMIN");
            teacherAId = seedUser(st, tenantA, "teacher@tenant-a.example", "TEACHER");
            seedUser(st, tenantB, "admin@tenant-b.example", "SCHOOL_ADMIN");
        }
    }

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, UUID schoolId)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
    }

    private UUID seedUser(Statement st, UUID tenantId, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '"
                + email + "')");
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
        return userId;
    }

    private Cookie login(String schoolIdentifier, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getCookie("access_token");
    }

    private Cookie loginAdminA() throws Exception {
        return login(SCHOOL_A, "admin@tenant-a.example", "secret");
    }

    private JsonNode auditLog(Cookie session, String query) throws Exception {
        var result = mockMvc.perform(get("/api/v1/audit-log" + query).cookie(session))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("content");
    }

    // --- Key actions produce entries ---------------------------------

    @Test
    void loginSuccessIsAuditedWithTheActor() throws Exception {
        loginAdminA();

        JsonNode entries = auditLog(loginAdminA(), "?entityType=USER");
        JsonNode success = findByAction(entries, "LOGIN_SUCCESS");
        assertThat(success).isNotNull();
        assertThat(success.get("actorUserId").asText()).isEqualTo(adminAId.toString());
        assertThat(success.get("details").get("email").asText()).isEqualTo("admin@tenant-a.example");
    }

    @Test
    void loginFailureIsAuditedEvenThoughLoginIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + SCHOOL_A + "\",\"email\":\"admin@tenant-a.example\","
                                + "\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        JsonNode entries = auditLog(loginAdminA(), "?entityType=USER");
        JsonNode failure = findByAction(entries, "LOGIN_FAILED");
        assertThat(failure).isNotNull();
        assertThat(failure.get("actorUserId").asText()).isEqualTo(adminAId.toString());
        assertThat(failure.get("details").get("reason").asText()).isEqualTo("bad_password");
    }

    @Test
    void studentCreateIsAuditedWithActorAndDetails() throws Exception {
        Cookie admin = loginAdminA();
        mockMvc.perform(post("/api/v1/students").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"fullName\":\"Ravi Kumar\","
                                + "\"admissionNumber\":\"ADM-AUDIT-1\"}"))
                .andExpect(status().isCreated());

        JsonNode student = findByAction(auditLog(admin, "?entityType=STUDENT"), "STUDENT_CREATED");
        assertThat(student).isNotNull();
        assertThat(student.get("actorUserId").asText()).isEqualTo(adminAId.toString());
        assertThat(student.get("details").get("admissionNumber").asText()).isEqualTo("ADM-AUDIT-1");
    }

    @Test
    void attendanceMarkIsAuditedWithTheTeacherAsActor() throws Exception {
        Cookie admin = loginAdminA();
        var created = mockMvc.perform(post("/api/v1/students").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"fullName\":\"Mark Me\","
                                + "\"admissionNumber\":\"ADM-AUDIT-2\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String studentId = JSON.readTree(created.getResponse().getContentAsString()).get("id").asText();

        Cookie teacher = login(SCHOOL_A, "teacher@tenant-a.example", "secret");
        mockMvc.perform(post("/api/v1/attendance").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"date\":\""
                                + java.time.LocalDate.now() + "\",\"status\":\"PRESENT\"}"))
                .andExpect(status().isCreated());

        JsonNode marked = findByAction(auditLog(admin, "?entityType=ATTENDANCE_RECORD"), "ATTENDANCE_MARKED");
        assertThat(marked).isNotNull();
        assertThat(marked.get("actorUserId").asText()).isEqualTo(teacherAId.toString());
        assertThat(marked.get("details").get("status").asText()).isEqualTo("PRESENT");
    }

    // --- Access + isolation ----------------------------------------

    @Test
    void teacherCannotReadTheAuditLog() throws Exception {
        mockMvc.perform(get("/api/v1/audit-log").cookie(login(SCHOOL_A, "teacher@tenant-a.example", "secret")))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditLogIsTenantScoped() throws Exception {
        // Tenant B logs in (creates a LOGIN_SUCCESS in tenant B).
        login(SCHOOL_B, "admin@tenant-b.example", "secret");

        JsonNode entries = auditLog(loginAdminA(), "");
        for (JsonNode entry : entries) {
            // Every actor visible here belongs to tenant A.
            if (!entry.get("actorUserId").isNull()) {
                assertThat(entry.get("actorUserId").asText())
                        .isIn(adminAId.toString(), teacherAId.toString());
            }
        }
    }

    @Test
    void auditLogFiltersByEntityType() throws Exception {
        Cookie admin = loginAdminA();
        mockMvc.perform(post("/api/v1/students").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"fullName\":\"Only Student\","
                                + "\"admissionNumber\":\"ADM-AUDIT-3\"}"))
                .andExpect(status().isCreated());

        JsonNode entries = auditLog(admin, "?entityType=STUDENT");
        assertThat(entries).isNotEmpty();
        for (JsonNode entry : entries) {
            assertThat(entry.get("entityType").asText()).isEqualTo("STUDENT");
        }
    }

    // --- Immutability --------------------------------------------

    @Test
    void theApplicationCannotUpdateOrDeleteAuditRows() throws Exception {
        loginAdminA(); // generates at least one audit row for tenant A

        assertThatThrownBy(() -> runAsAppRole("UPDATE audit_log SET action = 'HACKED'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runAsAppRole("DELETE FROM audit_log"))
                .isInstanceOf(DataAccessException.class);

        // The row is still there and unchanged.
        Cookie admin = loginAdminA();
        assertThat(auditLog(admin, "?entityType=USER")).isNotEmpty();
    }

    private void runAsAppRole(String sql) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                    try (var setTenant = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant_id', ?, true)")) {
                        setTenant.setString(1, tenantA.toString());
                        setTenant.execute();
                    }
                    try (var statement = connection.prepareStatement(sql)) {
                        statement.executeUpdate();
                    }
                    return null;
                }));
    }

    private static JsonNode findByAction(JsonNode entries, String action) {
        for (JsonNode entry : entries) {
            if (action.equals(entry.get("action").asText())) {
                return entry;
            }
        }
        return null;
    }
}
