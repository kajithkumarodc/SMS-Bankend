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
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit logging against a real PostgreSQL instance: key actions produce
 * entries with the right actor and details, the log is SCHOOL_ADMIN-only to
 * read, and the application cannot mutate audit rows (plan section 7.2 --
 * immutable audit trail).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        schoolA = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes, audit_log CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Tenant A School')");

            adminAId = seedUser(st, "admin@tenant-a.example", "SCHOOL_ADMIN");
            teacherAId = seedUser(st, "teacher@tenant-a.example", "TEACHER");
        }
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return userId;
    }

    private Cookie login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getCookie("access_token");
    }

    private Cookie loginAdminA() throws Exception {
        return login("admin@tenant-a.example", "secret");
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
                        .content("{\"email\":\"admin@tenant-a.example\",\"password\":\"wrong\"}"))
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
                        .content("{\"schoolId\":\"" + schoolA + "\",\"firstName\":\"Ravi\",\"lastName\":\"Kumar\","
                                + "\"admissionNumber\":\"ADM-AUDIT-1\",\"dateOfBirth\":\"2015-01-01\"}"))
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
                        .content("{\"schoolId\":\"" + schoolA + "\",\"firstName\":\"Mark\",\"lastName\":\"Me\","
                                + "\"admissionNumber\":\"ADM-AUDIT-2\",\"dateOfBirth\":\"2015-01-01\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String studentId = JSON.readTree(created.getResponse().getContentAsString()).get("id").asText();

        Cookie teacher = login("teacher@tenant-a.example", "secret");
        mockMvc.perform(post("/api/v1/attendance").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"date\":\""
                                + java.time.LocalDate.now() + "\",\"status\":\"PRESENT\"}"))
                .andExpect(status().isCreated());

        JsonNode marked = findByAction(auditLog(admin, "?entityType=ATTENDANCE_RECORD"), "ATTENDANCE_MARKED");
        assertThat(marked).isNotNull();
        assertThat(marked.get("actorUserId").asText()).isEqualTo(teacherAId.toString());
        assertThat(marked.get("details").get("status").asText()).isEqualTo("PRESENT");
    }

    // --- Access -----------------------------------------------------

    @Test
    void teacherCannotReadTheAuditLog() throws Exception {
        mockMvc.perform(get("/api/v1/audit-log").cookie(login("teacher@tenant-a.example", "secret")))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditLogFiltersByEntityType() throws Exception {
        Cookie admin = loginAdminA();
        mockMvc.perform(post("/api/v1/students").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"firstName\":\"Only\",\"lastName\":\"Student\","
                                + "\"admissionNumber\":\"ADM-AUDIT-3\",\"dateOfBirth\":\"2015-01-01\"}"))
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
        loginAdminA(); // generates at least one audit row

        assertThatThrownBy(() -> jdbcTemplate.execute("UPDATE audit_log SET action = 'HACKED'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.execute("DELETE FROM audit_log"))
                .isInstanceOf(DataAccessException.class);

        // The row is still there and unchanged.
        Cookie admin = loginAdminA();
        assertThat(auditLog(admin, "?entityType=USER")).isNotEmpty();
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
