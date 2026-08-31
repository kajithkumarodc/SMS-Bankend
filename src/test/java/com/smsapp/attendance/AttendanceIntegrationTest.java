package com.smsapp.attendance;

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
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Attendance module against a real PostgreSQL instance with RLS enabled:
 * tenant isolation, TEACHER may mark (unlike student creation), same-day
 * re-marking is an upsert, and future dates are rejected (plan section 2 / 7c).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceIntegrationTest {

    private static final String SCHOOL_A = "att-school-a";
    private static final String SCHOOL_B = "att-school-b";
    private static final String TODAY = LocalDate.now().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentA;
    private UUID studentB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID schoolA = UUID.randomUUID();
        UUID schoolB = UUID.randomUUID();
        studentA = UUID.randomUUID();
        studentB = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolA);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolB);

            seedUser(st, tenantA, "admin@tenant-a.example", "Admin A", "SCHOOL_ADMIN");
            seedUser(st, tenantA, "teacher@tenant-a.example", "Teacher A", "TEACHER");
            UUID adminB = seedUser(st, tenantB, "admin@tenant-b.example", "Admin B", "SCHOOL_ADMIN");

            seedStudent(st, tenantA, schoolA, studentA, "Student A", "ADM-A");
            seedStudent(st, tenantB, schoolB, studentB, "Student B", "ADM-B");

            // Tenant B already has a record for its own student, on the same date the
            // tests use -- a tenant-A query for that date must never see it.
            st.execute("INSERT INTO attendance_records (id, tenant_id, student_id, date, status, marked_by) VALUES ('"
                    + UUID.randomUUID() + "', '" + tenantB + "', '" + studentB + "', '" + TODAY + "', 'PRESENT', '"
                    + adminB + "')");
        }
    }

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, UUID schoolId)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
    }

    private UUID seedUser(Statement st, UUID tenantId, String email, String fullName, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '"
                + fullName + "')");
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
        return userId;
    }

    private static void seedStudent(Statement st, UUID tenantId, UUID schoolId, UUID studentId, String name,
                                    String admissionNumber) throws SQLException {
        st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status) VALUES ('"
                + studentId + "', '" + tenantId + "', '" + schoolId + "', '" + name + "', '" + admissionNumber
                + "', 'ACTIVE')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String markBody(UUID studentId, String date, String status) {
        return "{\"studentId\":\"" + studentId + "\",\"date\":\"" + date + "\",\"status\":\"" + status + "\"}";
    }

    // --- Who can mark ----------------------------------------------------

    @Test
    void teacherCanMarkAttendance() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .cookie(login(SCHOOL_A, "teacher@tenant-a.example"))
                        .contentType("application/json")
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.studentId").value(studentA.toString()))
                .andExpect(jsonPath("$.status").value("PRESENT"))
                .andExpect(jsonPath("$.markedBy").isString());
    }

    @Test
    void schoolAdminCanMarkAttendance() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .cookie(login(SCHOOL_A, "admin@tenant-a.example"))
                        .contentType("application/json")
                        .content(markBody(studentA, TODAY, "ABSENT")))
                .andExpect(status().isCreated());
    }

    @Test
    void markingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .contentType("application/json")
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isUnauthorized());
    }

    // --- Upsert --------------------------------------------------------

    @Test
    void reMarkingTheSameStudentAndDateUpdatesInsteadOfDuplicating() throws Exception {
        Cookie teacher = login(SCHOOL_A, "teacher@tenant-a.example");

        mockMvc.perform(post("/api/v1/attendance").cookie(teacher).contentType("application/json")
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isCreated());

        // Correction later the same day: not a 409, an update (200).
        mockMvc.perform(post("/api/v1/attendance").cookie(teacher).contentType("application/json")
                        .content(markBody(studentA, TODAY, "LATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LATE"));

        mockMvc.perform(get("/api/v1/attendance/student/" + studentA).cookie(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("LATE"));
    }

    // --- Validation ---------------------------------------------------

    @Test
    void futureDateIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .cookie(login(SCHOOL_A, "teacher@tenant-a.example"))
                        .contentType("application/json")
                        .content(markBody(studentA, LocalDate.now().plusDays(1).toString(), "PRESENT")))
                .andExpect(status().isBadRequest());
    }

    // --- Tenant isolation -------------------------------------------

    @Test
    void cannotMarkAttendanceForAnotherTenantsStudent() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .cookie(login(SCHOOL_A, "admin@tenant-a.example"))
                        .contentType("application/json")
                        .content(markBody(studentB, TODAY, "PRESENT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listByDateReturnsOnlyCallersTenantRecords() throws Exception {
        Cookie adminA = login(SCHOOL_A, "admin@tenant-a.example");
        mockMvc.perform(post("/api/v1/attendance").cookie(adminA).contentType("application/json")
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isCreated());

        // Tenant B also has a record for TODAY (seeded) -- must not appear here.
        mockMvc.perform(get("/api/v1/attendance").param("date", TODAY).cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].studentId").value(studentA.toString()));
    }

    @Test
    void studentHistoryForAnotherTenantsStudentReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/attendance/student/" + studentB)
                        .cookie(login(SCHOOL_A, "admin@tenant-a.example")))
                .andExpect(status().isNotFound());
    }
}
