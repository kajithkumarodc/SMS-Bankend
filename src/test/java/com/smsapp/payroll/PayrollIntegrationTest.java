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
 * Payroll generation + history against a real PostgreSQL instance with RLS
 * (plan section 2). Covers: correct {@code netPay} computation from the staff
 * member's on-file salary; SCHOOL_ADMIN-only generation (TEACHER/STUDENT/PARENT
 * 403); a clean 409 on a duplicate staff+month+year record; 404 when the staff
 * member has no profile; tenant isolation; and ownership isolation on
 * {@code /me/payroll}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PayrollIntegrationTest {

    private static final String SCHOOL_A = "payroll-a";
    private static final String SCHOOL_B = "payroll-b";
    private static final String ADMIN_A = "admin@payroll-a.example";
    private static final String TEACHER_A = "teacher@payroll-a.example";
    private static final String TEACHER_A2 = "teacher2@payroll-a.example";
    private static final String STUDENT_A = "student@payroll-a.example";
    private static final String PARENT_A = "parent@payroll-a.example";
    private static final String ADMIN_B = "admin@payroll-b.example";
    private static final String TEACHER_B = "teacher@payroll-b.example";
    private static final String PASSWORD = "secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherAUserId;
    private UUID teacherA2UserId;
    private UUID teacherBUserId;
    private UUID studentAUserId; // no staff profile -- used for the 404 case

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID schoolAId = UUID.randomUUID();
        UUID schoolBId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, "
                    + "staff_profiles, leave_requests, payroll_records, audit_log CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolAId);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolBId);

            UUID adminRoleA = seedRole(st, tenantA, "SCHOOL_ADMIN");
            UUID teacherRoleA = seedRole(st, tenantA, "TEACHER");
            UUID studentRoleA = seedRole(st, tenantA, "STUDENT");
            UUID parentRoleA = seedRole(st, tenantA, "PARENT");
            UUID adminRoleB = seedRole(st, tenantB, "SCHOOL_ADMIN");
            UUID teacherRoleB = seedRole(st, tenantB, "TEACHER");

            seedUser(st, tenantA, ADMIN_A, adminRoleA);
            teacherAUserId = seedUser(st, tenantA, TEACHER_A, teacherRoleA);
            teacherA2UserId = seedUser(st, tenantA, TEACHER_A2, teacherRoleA);
            studentAUserId = seedUser(st, tenantA, STUDENT_A, studentRoleA);
            seedUser(st, tenantA, PARENT_A, parentRoleA);
            seedUser(st, tenantB, ADMIN_B, adminRoleB);
            teacherBUserId = seedUser(st, tenantB, TEACHER_B, teacherRoleB);

            seedStaffProfile(st, tenantA, teacherAUserId, "EMP-A1", new BigDecimal("50000.00"));
            seedStaffProfile(st, tenantA, teacherA2UserId, "EMP-A2", new BigDecimal("40000.00"));
            seedStaffProfile(st, tenantB, teacherBUserId, "EMP-B1", new BigDecimal("45000.00"));
        }
    }

    // --- seed helpers ------------------------------------------------

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, UUID schoolId)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
    }

    private static UUID seedRole(Statement st, UUID tenantId, String role) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, UUID tenantId, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '"
                + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
        return userId;
    }

    private static void seedStaffProfile(Statement st, UUID tenantId, UUID userId, String employeeCode,
                                         BigDecimal salary) throws SQLException {
        st.execute("INSERT INTO staff_profiles (id, tenant_id, user_id, employee_code, date_of_joining, "
                + "salary_amount, status) VALUES ('" + UUID.randomUUID() + "', '" + tenantId + "', '" + userId
                + "', '" + employeeCode + "', '2020-01-01', " + salary + ", 'ACTIVE')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
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
        mockMvc.perform(post("/api/v1/payroll").cookie(login(SCHOOL_A, ADMIN_A))
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
            mockMvc.perform(post("/api/v1/payroll").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aDuplicatePayrollRecordForTheSameStaffMonthAndYearReturnsAClean409() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
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
        mockMvc.perform(post("/api/v1/payroll").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(studentAUserId, 3, 2026, "0.00")))
                .andExpect(status().isNotFound());
    }

    @Test
    void generatingPayrollForAnotherTenantsStaffMemberReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/payroll").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherBUserId, 3, 2026, "0.00")))
                .andExpect(status().isNotFound());
    }

    // --- /me/payroll: ownership isolation -------------------------

    @Test
    void ownPayrollIsScopedToTheCallersOwnHistory() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        mockMvc.perform(post("/api/v1/payroll").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherAUserId, 3, 2026, "0.00")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/payroll").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherA2UserId, 3, 2026, "0.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/me/payroll").cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].baseSalary").value(50000.00));

        mockMvc.perform(get("/api/v1/me/payroll").cookie(login(SCHOOL_A, TEACHER_A2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].baseSalary").value(40000.00));
    }

    @Test
    void ownPayrollNeverLeaksAnotherTenantsRecords() throws Exception {
        mockMvc.perform(post("/api/v1/payroll").cookie(login(SCHOOL_B, ADMIN_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(generateBody(teacherBUserId, 3, 2026, "0.00")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/me/payroll").cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void studentAndParentCannotReadPayrollHistory() throws Exception {
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/me/payroll").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }
}
