package com.smsapp.transport;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transport routes + vehicles + student assignment against a real PostgreSQL
 * instance with RLS (plan section 2). Covers: tenant isolation on routes,
 * vehicles, the route roster and the portal endpoints; SCHOOL_ADMIN-only
 * create / add / assign (TEACHER/STUDENT/PARENT 403); a clean 409 on a
 * duplicate registration number (and that the unique constraint is per-tenant);
 * cross-tenant route / student references returning 404 consistently; and
 * ownership isolation on {@code /me/student/transport} and
 * {@code /me/children/{id}/transport}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransportIntegrationTest {

    private static final String SCHOOL_A = "trans-a";
    private static final String SCHOOL_B = "trans-b";
    private static final String ADMIN_A = "admin@trans-a.example";
    private static final String TEACHER_A = "teacher@trans-a.example";
    private static final String STUDENT_A = "student@trans-a.example";   // linked to studentAId, on route A1
    private static final String PARENT_A = "parent@trans-a.example";     // guardian of studentAId
    private static final String PARENT_A2 = "parent2@trans-a.example";   // guardian of studentA2Id (no route)
    private static final String ADMIN_B = "admin@trans-b.example";
    private static final String STUDENT_B = "student@trans-b.example";   // linked to studentBId, on route B1
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentAId;    // "Anaya", tenant A, on route A1
    private UUID studentA2Id;   // "Bhavya", tenant A, no route
    private UUID studentBId;    // "Chandra", tenant B, on route B1
    private UUID routeA1Id;
    private UUID routeB1Id;

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
        studentAId = UUID.randomUUID();
        studentA2Id = UUID.randomUUID();
        studentBId = UUID.randomUUID();
        routeA1Id = UUID.randomUUID();
        routeB1Id = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, announcements, book_loans, library_books, "
                    + "transport_vehicles, transport_routes, audit_log CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolAId);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolBId);

            UUID adminRoleA = seedRole(st, tenantA, "SCHOOL_ADMIN");
            UUID teacherRoleA = seedRole(st, tenantA, "TEACHER");
            UUID studentRoleA = seedRole(st, tenantA, "STUDENT");
            UUID parentRoleA = seedRole(st, tenantA, "PARENT");
            UUID adminRoleB = seedRole(st, tenantB, "SCHOOL_ADMIN");
            UUID studentRoleB = seedRole(st, tenantB, "STUDENT");

            seedUser(st, tenantA, ADMIN_A, adminRoleA);
            seedUser(st, tenantA, TEACHER_A, teacherRoleA);
            UUID studentAUser = seedUser(st, tenantA, STUDENT_A, studentRoleA);
            UUID parentAUser = seedUser(st, tenantA, PARENT_A, parentRoleA);
            UUID parentA2User = seedUser(st, tenantA, PARENT_A2, parentRoleA);
            seedUser(st, tenantB, ADMIN_B, adminRoleB);
            UUID studentBUser = seedUser(st, tenantB, STUDENT_B, studentRoleB);

            seedRoute(st, tenantA, routeA1Id, "Route A1 - North");
            seedRoute(st, tenantB, routeB1Id, "Route B1 - South");
            seedVehicle(st, tenantA, routeA1Id, "KA01AA1111", "Ravi Kumar", "+91 90000 00001", 40);
            seedVehicle(st, tenantB, routeB1Id, "KA05BB2222", "Suresh Rao", "+91 90000 00002", 30);

            seedStudent(st, tenantA, schoolAId, studentAId, "Anaya", studentAUser, parentAUser, routeA1Id);
            seedStudent(st, tenantA, schoolAId, studentA2Id, "Bhavya", null, parentA2User, null);
            seedStudent(st, tenantB, schoolBId, studentBId, "Chandra", studentBUser, null, routeB1Id);
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

    private static void seedRoute(Statement st, UUID tenantId, UUID id, String name) throws SQLException {
        st.execute("INSERT INTO transport_routes (id, tenant_id, name) VALUES ('"
                + id + "', '" + tenantId + "', '" + name + "')");
    }

    private static void seedVehicle(Statement st, UUID tenantId, UUID routeId, String registration,
                                    String driverName, String driverContact, int capacity) throws SQLException {
        st.execute("INSERT INTO transport_vehicles (id, tenant_id, route_id, registration_number, driver_name, "
                + "driver_contact, capacity) VALUES ('" + UUID.randomUUID() + "', '" + tenantId + "', '" + routeId
                + "', '" + registration + "', '" + driverName + "', '" + driverContact + "', " + capacity + ")");
    }

    private static void seedStudent(Statement st, UUID tenantId, UUID schoolId, UUID studentId, String name,
                                    UUID studentUserId, UUID guardianUserId, UUID transportRouteId)
            throws SQLException {
        st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status, "
                + "student_user_id, guardian_user_id, transport_route_id) VALUES ('" + studentId + "', '" + tenantId
                + "', '" + schoolId + "', '" + name + "', 'ADM-" + name + "', 'ACTIVE', "
                + (studentUserId == null ? "NULL" : "'" + studentUserId + "'") + ", "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ", "
                + (transportRouteId == null ? "NULL" : "'" + transportRouteId + "'") + ")");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID createRoute(Cookie admin, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/transport/routes").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Routes: SCHOOL_ADMIN only + tenant isolation ---------

    @Test
    void schoolAdminCreatesARouteAndListingIsTenantScoped() throws Exception {
        createRoute(login(SCHOOL_A, ADMIN_A), "Route A2 - East");

        mockMvc.perform(get("/api/v1/transport/routes").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)) // A1 (seeded) + A2
                .andExpect(jsonPath("$[?(@.name == 'Route B1 - South')]").isEmpty());

        mockMvc.perform(get("/api/v1/transport/routes").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Route B1 - South"));
    }

    @Test
    void teacherStudentAndParentCannotCreateARoute() throws Exception {
        String body = "{\"name\":\"X\"}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/transport/routes").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Vehicles: SCHOOL_ADMIN only, 409, cross-tenant 404 ---

    @Test
    void schoolAdminAddsAVehicleOptionallyOnARoute() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);

        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01CC3333\",\"driverName\":\"Manoj\","
                                + "\"driverContact\":\"+91 90000 00003\",\"capacity\":35,\"routeId\":\"" + routeA1Id + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.registrationNumber").value("KA01CC3333"))
                .andExpect(jsonPath("$.routeId").value(routeA1Id.toString()))
                .andExpect(jsonPath("$.capacity").value(35));

        // Unassigned vehicle (no routeId).
        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01DD4444\",\"driverName\":\"Deepak\",\"capacity\":20}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.routeId").doesNotExist());
    }

    @Test
    void teacherCannotAddAVehicle() throws Exception {
        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01EE5555\",\"driverName\":\"X\",\"capacity\":10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDuplicateRegistrationNumberReturnsAClean409ButTheConstraintIsPerTenant() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);

        // "KA01AA1111" is already seeded in tenant A.
        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01AA1111\",\"driverName\":\"Someone\",\"capacity\":40}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("KA01AA1111")));

        // Tenant B may use the same registration number -- the unique key is (tenant_id, registration_number).
        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(login(SCHOOL_B, ADMIN_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01AA1111\",\"driverName\":\"Other\",\"capacity\":40}"))
                .andExpect(status().isCreated());
    }

    @Test
    void addingAVehicleOnAnotherTenantsRouteReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01FF6666\",\"driverName\":\"X\",\"capacity\":10,"
                                + "\"routeId\":\"" + routeB1Id + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void vehicleListIsTenantScopedAndFiltersByRoute() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID otherRoute = createRoute(adminA, "Route A9");

        mockMvc.perform(get("/api/v1/transport/vehicles").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].registrationNumber").value("KA01AA1111"));

        mockMvc.perform(get("/api/v1/transport/vehicles").param("routeId", routeA1Id.toString()).cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/transport/vehicles").param("routeId", otherRoute.toString()).cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Tenant A cannot see tenant B's vehicle.
        mockMvc.perform(get("/api/v1/transport/vehicles").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].registrationNumber").value("KA05BB2222"));
    }

    // --- Student assignment ----------------------------------

    @Test
    void schoolAdminAssignsAndUnassignsAStudentsRoute() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);

        // Bhavya (studentA2Id) starts with no route.
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/transport-route").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeA1Id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transportRouteId").value(routeA1Id.toString()));

        // Route roster now has both Anaya and Bhavya.
        mockMvc.perform(get("/api/v1/transport/routes/" + routeA1Id + "/students").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].fullName", org.hamcrest.Matchers.containsInAnyOrder("Anaya", "Bhavya")));

        // Unassign with a null routeId.
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/transport-route").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"routeId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transportRouteId").doesNotExist());

        mockMvc.perform(get("/api/v1/transport/routes/" + routeA1Id + "/students").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void onlySchoolAdminCanAssignAStudentsRoute() throws Exception {
        String body = "{\"routeId\":\"" + routeA1Id + "\"}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(patch("/api/v1/students/" + studentAId + "/transport-route").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void assigningAcrossTenantsReturns404Consistently() throws Exception {
        // Tenant A admin, tenant B route.
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/transport-route").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeB1Id + "\"}"))
                .andExpect(status().isNotFound());

        // Tenant B admin, tenant A student.
        mockMvc.perform(patch("/api/v1/students/" + studentAId + "/transport-route").cookie(login(SCHOOL_B, ADMIN_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":null}"))
                .andExpect(status().isNotFound());
    }

    // --- Route roster (staff) --------------------------------

    @Test
    void routeRosterIsTenantScopedAndStaffOnly() throws Exception {
        mockMvc.perform(get("/api/v1/transport/routes/" + routeA1Id + "/students").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fullName").value("Anaya"));

        // Teacher may read it too.
        mockMvc.perform(get("/api/v1/transport/routes/" + routeA1Id + "/students").cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk());

        // Tenant A cannot read a tenant-B route's roster.
        mockMvc.perform(get("/api/v1/transport/routes/" + routeB1Id + "/students").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isNotFound());

        // Student / parent may not use the staff endpoint.
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/transport/routes/" + routeA1Id + "/students").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Portal: ownership isolation -------------------------

    @Test
    void studentSeesOnlyTheirOwnTransportAssignment() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/transport").cookie(login(SCHOOL_A, STUDENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeName").value("Route A1 - North"))
                .andExpect(jsonPath("$.vehicles[0].registrationNumber").value("KA01AA1111"))
                .andExpect(jsonPath("$.vehicles[0].driverName").value("Ravi Kumar"));

        // Tenant B student sees their own (Tenant B) route, never tenant A's.
        mockMvc.perform(get("/api/v1/me/student/transport").cookie(login(SCHOOL_B, STUDENT_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeName").value("Route B1 - South"));
    }

    @Test
    void studentWithNoRouteGetsACleanNotFound() throws Exception {
        // Give STUDENT_A's login a student with no route by unassigning Anaya first.
        mockMvc.perform(patch("/api/v1/students/" + studentAId + "/transport-route").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"routeId\":null}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me/student/transport").cookie(login(SCHOOL_A, STUDENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void parentSeesTheirOwnChildsTransportButNotAnothersOrAnotherTenants() throws Exception {
        // PARENT_A is Anaya's (studentAId) guardian; Anaya is on route A1.
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/transport").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeName").value("Route A1 - North"));

        // Bhavya (studentA2Id) is PARENT_A2's child, not PARENT_A's -> 404, same tenant.
        mockMvc.perform(get("/api/v1/me/children/" + studentA2Id + "/transport").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());

        // A tenant-A parent asking for a tenant-B student's id -> 404, never a leak.
        mockMvc.perform(get("/api/v1/me/children/" + studentBId + "/transport").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePortalTransportEndpointsAreRoleGated() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/transport").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/transport").cookie(login(SCHOOL_A, STUDENT_A)))
                .andExpect(status().isForbidden());
    }
}
