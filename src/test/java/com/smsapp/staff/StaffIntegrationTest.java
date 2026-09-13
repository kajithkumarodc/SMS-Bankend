package com.smsapp.staff;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff profiles + leave requests against a real PostgreSQL instance with RLS
 * (plan section 2). Covers: tenant isolation on profiles and leave requests;
 * SCHOOL_ADMIN-only create/update/approve (TEACHER/STUDENT/PARENT 403); a clean
 * 409 on a duplicate user or employee code; cross-tenant references returning
 * 404 consistently; the leave-request ownership check (a TEACHER may only file
 * for themselves, 403 for someone else, SCHOOL_ADMIN may file for anyone); and
 * ownership isolation on {@code /me/leave-requests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffIntegrationTest {

    private static final String SCHOOL_A = "staff-a";
    private static final String SCHOOL_B = "staff-b";
    private static final String ADMIN_A = "admin@staff-a.example";
    private static final String TEACHER_A = "teacher@staff-a.example";   // has a staff profile
    private static final String TEACHER_A2 = "teacher2@staff-a.example"; // has a staff profile
    private static final String STUDENT_A = "student@staff-a.example";
    private static final String PARENT_A = "parent@staff-a.example";
    private static final String ADMIN_B = "admin@staff-b.example";
    private static final String TEACHER_B = "teacher@staff-b.example";
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherAUserId;
    private UUID teacherA2UserId;
    private UUID teacherBUserId;
    private UUID adminBUserId; // no staff profile seeded -- free to attach one in tests
    private UUID studentAUserId;

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
            adminBUserId = seedUser(st, tenantB, ADMIN_B, adminRoleB);
            teacherBUserId = seedUser(st, tenantB, TEACHER_B, teacherRoleB);

            seedStaffProfile(st, tenantA, teacherAUserId, "EMP-A1", new java.math.BigDecimal("50000.00"));
            seedStaffProfile(st, tenantA, teacherA2UserId, "EMP-A2", new java.math.BigDecimal("40000.00"));
            seedStaffProfile(st, tenantB, teacherBUserId, "EMP-B1", new java.math.BigDecimal("45000.00"));
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
                                         java.math.BigDecimal salary) throws SQLException {
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

    private JsonNode readJson(String content) throws Exception {
        return JSON.readTree(content);
    }

    private UUID staffProfileId(Cookie admin, UUID userId) throws Exception {
        var result = mockMvc.perform(get("/api/v1/staff").cookie(admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode profiles = readJson(result.getResponse().getContentAsString());
        for (JsonNode p : profiles) {
            if (p.get("userId").asText().equals(userId.toString())) {
                return UUID.fromString(p.get("id").asText());
            }
        }
        throw new IllegalStateException("No staff profile found for user " + userId);
    }

    // --- Staff profiles: SCHOOL_ADMIN only + tenant isolation ---

    @Test
    void schoolAdminCreatesAProfileAndListingIsTenantScoped() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);

        mockMvc.perform(post("/api/v1/staff").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + studentAUserId + "\",\"employeeCode\":\"EMP-A3\","
                                + "\"department\":\"Admin\",\"designation\":\"Clerk\","
                                + "\"dateOfJoining\":\"2022-05-01\",\"salaryAmount\":30000.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeCode").value("EMP-A3"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/staff").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3)) // A1, A2 (seeded) + A3
                .andExpect(jsonPath("$[?(@.employeeCode == 'EMP-B1')]").isEmpty());

        mockMvc.perform(get("/api/v1/staff").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].employeeCode").value("EMP-B1"));
    }

    @Test
    void teacherStudentAndParentCannotCreateOrListStaffProfiles() throws Exception {
        String body = "{\"userId\":\"" + studentAUserId + "\",\"employeeCode\":\"EMP-X\","
                + "\"dateOfJoining\":\"2022-01-01\",\"salaryAmount\":10000.00}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/staff").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/staff").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void creatingAProfileForAUserNotInTheTenantReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/staff").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + teacherBUserId + "\",\"employeeCode\":\"EMP-X\","
                                + "\"dateOfJoining\":\"2022-01-01\",\"salaryAmount\":10000.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aUserWhoAlreadyHasAProfileReturnsAClean409() throws Exception {
        mockMvc.perform(post("/api/v1/staff").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + teacherAUserId + "\",\"employeeCode\":\"EMP-DUP\","
                                + "\"dateOfJoining\":\"2022-01-01\",\"salaryAmount\":10000.00}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aDuplicateEmployeeCodeReturnsAClean409ButTheConstraintIsPerTenant() throws Exception {
        // "EMP-A1" is already seeded in tenant A.
        mockMvc.perform(post("/api/v1/staff").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + studentAUserId + "\",\"employeeCode\":\"EMP-A1\","
                                + "\"dateOfJoining\":\"2022-01-01\",\"salaryAmount\":10000.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("EMP-A1")));

        // Tenant B may reuse the same employee code -- the unique key is (tenant_id, employee_code).
        // adminBUserId has no staff profile yet, so this only exercises the employee-code uniqueness.
        mockMvc.perform(post("/api/v1/staff").cookie(login(SCHOOL_B, ADMIN_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + adminBUserId + "\",\"employeeCode\":\"EMP-A1\","
                                + "\"dateOfJoining\":\"2022-01-01\",\"salaryAmount\":10000.00}"))
                .andExpect(status().isCreated());
    }

    @Test
    void schoolAdminUpdatesAProfileAndATeacherCannot() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID profileId = staffProfileId(adminA, teacherAUserId);

        mockMvc.perform(put("/api/v1/staff/" + profileId).cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"department\":\"Science\",\"designation\":\"HOD\","
                                + "\"dateOfJoining\":\"2020-01-01\",\"salaryAmount\":65000.00,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("Science"))
                .andExpect(jsonPath("$.salaryAmount").value(65000.00));

        mockMvc.perform(put("/api/v1/staff/" + profileId).cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"department\":\"X\",\"dateOfJoining\":\"2020-01-01\","
                                + "\"salaryAmount\":1.00,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updatingAProfileFromAnotherTenantReturns404() throws Exception {
        Cookie adminB = login(SCHOOL_B, ADMIN_B);
        UUID profileBId = staffProfileId(adminB, teacherBUserId);

        mockMvc.perform(put("/api/v1/staff/" + profileBId).cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateOfJoining\":\"2020-01-01\",\"salaryAmount\":1.00,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound());
    }

    // --- Leave requests: ownership + SCHOOL_ADMIN approval -------

    @Test
    void aTeacherCanFileTheirOwnLeaveRequest() throws Exception {
        Cookie teacherA = login(SCHOOL_A, TEACHER_A);
        UUID profileId = staffProfileId(login(SCHOOL_A, ADMIN_A), teacherAUserId);

        mockMvc.perform(post("/api/v1/staff/" + profileId + "/leave-requests").cookie(teacherA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\","
                                + "\"endDate\":\"2026-03-03\",\"reason\":\"Flu\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.staffUserId").value(teacherAUserId.toString()));
    }

    @Test
    void aTeacherCannotFileLeaveOnBehalfOfAnotherStaffMember() throws Exception {
        Cookie teacherA = login(SCHOOL_A, TEACHER_A);
        UUID otherProfileId = staffProfileId(login(SCHOOL_A, ADMIN_A), teacherA2UserId);

        mockMvc.perform(post("/api/v1/staff/" + otherProfileId + "/leave-requests").cookie(teacherA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSchoolAdminMayFileLeaveOnBehalfOfAnyStaffMember() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID profileId = staffProfileId(adminA, teacherA2UserId);

        mockMvc.perform(post("/api/v1/staff/" + profileId + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"CASUAL\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-04-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffUserId").value(teacherA2UserId.toString()));
    }

    @Test
    void studentAndParentCannotFileALeaveRequest() throws Exception {
        UUID profileId = staffProfileId(login(SCHOOL_A, ADMIN_A), teacherAUserId);
        String body = "{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}";
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/staff/" + profileId + "/leave-requests").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void filingLeaveAgainstAProfileNotInTheTenantReturns404() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID profileBId = staffProfileId(login(SCHOOL_B, ADMIN_B), teacherBUserId);

        mockMvc.perform(post("/api/v1/staff/" + profileBId + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlySchoolAdminCanApproveOrRejectALeaveRequest() throws Exception {
        Cookie teacherA = login(SCHOOL_A, TEACHER_A);
        UUID profileId = staffProfileId(login(SCHOOL_A, ADMIN_A), teacherAUserId);
        var result = mockMvc.perform(post("/api/v1/staff/" + profileId + "/leave-requests").cookie(teacherA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID leaveId = UUID.fromString(readJson(result.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(patch("/api/v1/leave-requests/" + leaveId).cookie(teacherA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/leave-requests/" + leaveId).cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void approvingWithAnInvalidStatusReturns400() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID profileId = staffProfileId(adminA, teacherAUserId);
        var result = mockMvc.perform(post("/api/v1/staff/" + profileId + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID leaveId = UUID.fromString(readJson(result.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(patch("/api/v1/leave-requests/" + leaveId).cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void approvingALeaveRequestFromAnotherTenantReturns404() throws Exception {
        Cookie adminB = login(SCHOOL_B, ADMIN_B);
        UUID profileBId = staffProfileId(adminB, teacherBUserId);
        var result = mockMvc.perform(post("/api/v1/staff/" + profileBId + "/leave-requests").cookie(adminB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID leaveBId = UUID.fromString(readJson(result.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(patch("/api/v1/leave-requests/" + leaveBId).cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());
    }

    // --- /me/leave-requests: ownership isolation -----------------

    @Test
    void ownLeaveRequestsAreScopedToTheCallersOwnHistory() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID teacherAProfile = staffProfileId(adminA, teacherAUserId);
        UUID teacherA2Profile = staffProfileId(adminA, teacherA2UserId);

        mockMvc.perform(post("/api/v1/staff/" + teacherAProfile + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/staff/" + teacherA2Profile + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"CASUAL\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-04-01\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/me/leave-requests").cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].leaveType").value("SICK"));

        mockMvc.perform(get("/api/v1/me/leave-requests").cookie(login(SCHOOL_A, TEACHER_A2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].leaveType").value("CASUAL"));
    }

    @Test
    void studentAndParentCannotReadLeaveRequestHistory() throws Exception {
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/me/leave-requests").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- /staff/eligible-users --------------------------------------

    @Test
    void eligibleUsersExcludesUsersWhoAlreadyHaveAProfileAndIsTenantScoped() throws Exception {
        mockMvc.perform(get("/api/v1/staff/eligible-users").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isOk())
                // ADMIN_A + STUDENT_A + PARENT_A have no profile; TEACHER_A/TEACHER_A2 already do.
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].email", org.hamcrest.Matchers.containsInAnyOrder(
                        ADMIN_A, STUDENT_A, PARENT_A)));
    }

    @Test
    void onlySchoolAdminCanListEligibleUsers() throws Exception {
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/staff/eligible-users").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- /me/staff-profile --------------------------------------------

    @Test
    void aTeacherSeesTheirOwnStaffProfile() throws Exception {
        mockMvc.perform(get("/api/v1/me/staff-profile").cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeCode").value("EMP-A1"))
                .andExpect(jsonPath("$.userId").value(teacherAUserId.toString()));
    }

    @Test
    void aUserWithNoStaffProfileGetsACleanNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/me/staff-profile").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void studentAndParentCannotReadOwnStaffProfile() throws Exception {
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/me/staff-profile").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- GET /leave-requests: SCHOOL_ADMIN view, filters, tenant scope --

    @Test
    void listLeaveRequestsWithNoFilterReturnsTheWholeTenant() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID profileId = staffProfileId(adminA, teacherAUserId);
        mockMvc.perform(post("/api/v1/staff/" + profileId + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/leave-requests").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Tenant B's admin sees none of tenant A's requests.
        mockMvc.perform(get("/api/v1/leave-requests").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listLeaveRequestsFiltersByStatusForThePendingQueue() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID profileId = staffProfileId(adminA, teacherAUserId);
        var result = mockMvc.perform(post("/api/v1/staff/" + profileId + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID leaveId = UUID.fromString(readJson(result.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(patch("/api/v1/leave-requests/" + leaveId).cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        UUID profileId2 = staffProfileId(adminA, teacherA2UserId);
        mockMvc.perform(post("/api/v1/staff/" + profileId2 + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"CASUAL\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-04-01\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/leave-requests").param("status", "PENDING").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].leaveType").value("CASUAL"));
    }

    @Test
    void listLeaveRequestsFiltersByStaffMemberForTheDetailView() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID profileId = staffProfileId(adminA, teacherAUserId);
        UUID profileId2 = staffProfileId(adminA, teacherA2UserId);
        mockMvc.perform(post("/api/v1/staff/" + profileId + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"SICK\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-03-03\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/staff/" + profileId2 + "/leave-requests").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaveType\":\"CASUAL\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-04-01\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/leave-requests").param("staffUserId", teacherAUserId.toString()).cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].leaveType").value("SICK"));
    }

    @Test
    void listLeaveRequestsWithAnInvalidStatusReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/leave-requests").param("status", "CANCELLED").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherStudentAndParentCannotListLeaveRequests() throws Exception {
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/leave-requests").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }
}
