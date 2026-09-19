package com.smsapp.payroll;

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

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payroll generation + history against a real PostgreSQL instance (plan
 * section 2). Covers: correct {@code netPay} computation from the staff
 * member's on-file salary; SCHOOL_ADMIN-only generation (TEACHER/STUDENT/PARENT
 * 403); a clean 409 on a duplicate staff+month+year record; 404 when the staff
 * member has no profile / doesn't exist; and ownership isolation on
 * {@code /me/payroll}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PayrollIntegrationTest {

    private static final String ADMIN_A = "admin@payroll-a.example";
    private static final String TEACHER_A = "teacher@payroll-a.example";
    private static final String TEACHER_A2 = "teacher2@payroll-a.example";
    private static final String STUDENT_A = "student@payroll-a.example";
    private static final String PARENT_A = "parent@payroll-a.example";
    private static final String PASSWORD = "secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherAUserId;
    private UUID teacherA2UserId;
    private UUID studentAUserId; // no staff profile -- used for the 404 case

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID schoolAId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, "
                    + "staff_profiles, leave_requests, payroll_records, audit_log CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolAId + "', 'Tenant A School')");

            UUID adminRoleA = seedRole(st, "SCHOOL_ADMIN");
            UUID teacherRoleA = seedRole(st, "TEACHER");
            UUID studentRoleA = seedRole(st, "STUDENT");
            UUID parentRoleA = seedRole(st, "PARENT");

            seedUser(st, ADMIN_A, adminRoleA);
            teacherAUserId = seedUser(st, TEACHER_A, teacherRoleA);
            teacherA2UserId = seedUser(st, TEACHER_A2, teacherRoleA);
            studentAUserId = seedUser(st, STUDENT_A, studentRoleA);
            seedUser(st, PARENT_A, parentRoleA);

            seedStaffProfile(st, teacherAUserId, "EMP-A1", new BigDecimal("50000.00"));
            seedStaffProfile(st, teacherA2UserId, "EMP-A2", new BigDecimal("40000.00"));
        }
    }

    // --- seed helpers ------------------------------------------------

    private static UUID seedRole(Statement st, String role) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '" + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return userId;
    }

    private static void seedStaffProfile(Statement st, UUID userId, String employeeCode, BigDecimal salary)
            throws SQLException {
        st.execute("INSERT INTO staff_profiles (id, user_id, employee_code, date_of_joining, "
                + "salary_amount, status) VALUES ('" + UUID.randomUUID() + "', '" + userId
                + "', '" + employeeCode + "', '2020-01-01', " + salary + ", 'ACTIVE')");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String generateBody(UUID staffUserId, int month, int year, String deductions) {
        return "{\"staffUserId\":\"" + staffUserId + "\",\"month\":" + month + ",\"year\":" + year
                + ",\"deductions\":" + deductions + "}";
    }

    // --- generate: SCHOOL_ADMIN only, net_pay, 409, 404 -----------

    @Test
    void schoolAdminGeneratesPayrollWithTheCorrectNetPay() throws Exception {
        mockMvc.perform(post("/api/v1/payroll").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherAUserId, 3, 2026, "5000.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baseSalary").value(50000.00))
                .andExpect(jsonPath("$.deductions").value(5000.00))
                .andExpect(jsonPath("$.netPay").value(45000.00))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void teacherStudentAndParentCannotGeneratePayroll() throws Exception {
        String body = generateBody(teacherAUserId, 3, 2026, "0.00");
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/payroll").cookie(login(email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aDuplicatePayrollRecordForTheSameStaffMonthAndYearReturnsAClean409() throws Exception {
        Cookie adminA = login(ADMIN_A);
        mockMvc.perform(post("/api/v1/payroll").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherAUserId, 3, 2026, "0.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/payroll").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherAUserId, 3, 2026, "1000.00")))
                .andExpect(status().isConflict());

        // A different month is fine.
        mockMvc.perform(post("/api/v1/payroll").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherAUserId, 4, 2026, "0.00")))
                .andExpect(status().isCreated());
    }

    @Test
    void generatingPayrollForAStaffMemberWithNoProfileReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/payroll").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(studentAUserId, 3, 2026, "0.00")))
                .andExpect(status().isNotFound());
    }

    @Test
    void generatingPayrollForANonexistentStaffMemberReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/payroll").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(UUID.randomUUID(), 3, 2026, "0.00")))
                .andExpect(status().isNotFound());
    }

    // --- /me/payroll: ownership isolation -------------------------

    @Test
    void ownPayrollIsScopedToTheCallersOwnHistory() throws Exception {
        Cookie adminA = login(ADMIN_A);
        mockMvc.perform(post("/api/v1/payroll").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherAUserId, 3, 2026, "0.00")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/payroll").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherA2UserId, 3, 2026, "0.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/me/payroll").cookie(login(TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].baseSalary").value(50000.00));

        mockMvc.perform(get("/api/v1/me/payroll").cookie(login(TEACHER_A2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].baseSalary").value(40000.00));
    }

    @Test
    void studentAndParentCannotReadPayrollHistory() throws Exception {
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/me/payroll").cookie(login(email)))
                    .andExpect(status().isForbidden());
        }
    }
}
