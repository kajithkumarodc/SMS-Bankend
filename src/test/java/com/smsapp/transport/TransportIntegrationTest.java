package com.smsapp.transport;

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
 * instance (plan section 2). Covers: SCHOOL_ADMIN-only create / add / assign
 * (TEACHER/STUDENT/PARENT 403); a clean 409 on a duplicate registration number
 * (globally unique since V18); a nonexistent route/student reference returning
 * 404 consistently; and ownership isolation on {@code /me/student/transport}
 * and {@code /me/children/{id}/transport}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransportIntegrationTest {

    private static final String ADMIN_A = "admin@trans-a.example";
    private static final String TEACHER_A = "teacher@trans-a.example";
    private static final String STUDENT_A = "student@trans-a.example";   // linked to studentAId, on route A1
    private static final String PARENT_A = "parent@trans-a.example";     // guardian of studentAId
    private static final String PARENT_A2 = "parent2@trans-a.example";   // guardian of studentA2Id (no route)
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentAId;    // "Anaya", on route A1
    private UUID studentA2Id;   // "Bhavya", no route
    private UUID routeA1Id;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID schoolAId = UUID.randomUUID();
        studentAId = UUID.randomUUID();
        studentA2Id = UUID.randomUUID();
        routeA1Id = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, announcements, book_loans, library_books, "
                    + "transport_vehicles, transport_routes, audit_log CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolAId + "', 'Tenant A School')");

            UUID adminRoleA = seedRole(st, "SCHOOL_ADMIN");
            UUID teacherRoleA = seedRole(st, "TEACHER");
            UUID studentRoleA = seedRole(st, "STUDENT");
            UUID parentRoleA = seedRole(st, "PARENT");

            seedUser(st, ADMIN_A, adminRoleA);
            seedUser(st, TEACHER_A, teacherRoleA);
            UUID studentAUser = seedUser(st, STUDENT_A, studentRoleA);
            UUID parentAUser = seedUser(st, PARENT_A, parentRoleA);
            UUID parentA2User = seedUser(st, PARENT_A2, parentRoleA);

            seedRoute(st, routeA1Id, "Route A1 - North");
            seedVehicle(st, routeA1Id, "KA01AA1111", "Ravi Kumar", "+91 90000 00001", 40);

            seedStudent(st, schoolAId, studentAId, "Anaya", studentAUser, parentAUser, routeA1Id);
            seedStudent(st, schoolAId, studentA2Id, "Bhavya", null, parentA2User, null);
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

    private static void seedRoute(Statement st, UUID id, String name) throws SQLException {
        st.execute("INSERT INTO transport_routes (id, name) VALUES ('" + id + "', '" + name + "')");
    }

    private static void seedVehicle(Statement st, UUID routeId, String registration, String driverName,
                                    String driverContact, int capacity) throws SQLException {
        st.execute("INSERT INTO transport_vehicles (id, route_id, registration_number, driver_name, "
                + "driver_contact, capacity) VALUES ('" + UUID.randomUUID() + "', '" + routeId
                + "', '" + registration + "', '" + driverName + "', '" + driverContact + "', " + capacity + ")");
    }

    private static void seedStudent(Statement st, UUID schoolId, UUID studentId, String name,
                                    UUID studentUserId, UUID guardianUserId, UUID transportRouteId)
            throws SQLException {
        st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, status, "
                + "student_user_id, guardian_user_id, transport_route_id) VALUES ('" + studentId
                + "', '" + schoolId + "', '" + name + "', '" + name + "', '" + name + "', 'ADM-" + name + "', 'ACTIVE', "
                + (studentUserId == null ? "NULL" : "'" + studentUserId + "'") + ", "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ", "
                + (transportRouteId == null ? "NULL" : "'" + transportRouteId + "'") + ")");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
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

    // --- Routes: SCHOOL_ADMIN only ---------

    @Test
    void schoolAdminCreatesARoute() throws Exception {
        createRoute(login(ADMIN_A), "Route A2 - East");

        mockMvc.perform(get("/api/v1/transport/routes").cookie(login(ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)); // A1 (seeded) + A2
    }

    @Test
    void teacherStudentAndParentCannotCreateARoute() throws Exception {
        String body = "{\"name\":\"X\"}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/transport/routes").cookie(login(email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Vehicles: SCHOOL_ADMIN only, 409, existence checks ---

    @Test
    void schoolAdminAddsAVehicleOptionallyOnARoute() throws Exception {
        Cookie admin = login(ADMIN_A);

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
        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01EE5555\",\"driverName\":\"X\",\"capacity\":10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDuplicateRegistrationNumberReturnsAClean409() throws Exception {
        Cookie adminA = login(ADMIN_A);

        // "KA01AA1111" is already seeded.
        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01AA1111\",\"driverName\":\"Someone\",\"capacity\":40}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("KA01AA1111")));
    }

    @Test
    void addingAVehicleOnANonexistentRouteReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/transport/vehicles").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"KA01FF6666\",\"driverName\":\"X\",\"capacity\":10,"
                                + "\"routeId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void vehicleListFiltersByRoute() throws Exception {
        Cookie adminA = login(ADMIN_A);
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
    }

    // --- Student assignment ----------------------------------

    @Test
    void schoolAdminAssignsAndUnassignsAStudentsRoute() throws Exception {
        Cookie adminA = login(ADMIN_A);

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
            mockMvc.perform(patch("/api/v1/students/" + studentAId + "/transport-route").cookie(login(email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void assigningANonexistentRouteOrStudentReturns404Consistently() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/transport-route").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/students/" + UUID.randomUUID() + "/transport-route").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":null}"))
                .andExpect(status().isNotFound());
    }

    // --- Route roster (staff) --------------------------------

    @Test
    void routeRosterIsStaffOnly() throws Exception {
        mockMvc.perform(get("/api/v1/transport/routes/" + routeA1Id + "/students").cookie(login(ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fullName").value("Anaya"));

        // Teacher may read it too.
        mockMvc.perform(get("/api/v1/transport/routes/" + routeA1Id + "/students").cookie(login(TEACHER_A)))
                .andExpect(status().isOk());

        // A nonexistent route's roster is 404.
        mockMvc.perform(get("/api/v1/transport/routes/" + UUID.randomUUID() + "/students").cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());

        // Student / parent may not use the staff endpoint.
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/transport/routes/" + routeA1Id + "/students").cookie(login(email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Portal: ownership isolation -------------------------

    @Test
    void studentSeesOnlyTheirOwnTransportAssignment() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/transport").cookie(login(STUDENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeName").value("Route A1 - North"))
                .andExpect(jsonPath("$.vehicles[0].registrationNumber").value("KA01AA1111"))
                .andExpect(jsonPath("$.vehicles[0].driverName").value("Ravi Kumar"));
    }

    @Test
    void studentWithNoRouteGetsACleanNotFound() throws Exception {
        // Give STUDENT_A's login a student with no route by unassigning Anaya first.
        mockMvc.perform(patch("/api/v1/students/" + studentAId + "/transport-route").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"routeId\":null}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me/student/transport").cookie(login(STUDENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void parentSeesTheirOwnChildsTransportButNotAnothers() throws Exception {
        // PARENT_A is Anaya's (studentAId) guardian; Anaya is on route A1.
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/transport").cookie(login(PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeName").value("Route A1 - North"));

        // Bhavya (studentA2Id) is PARENT_A2's child, not PARENT_A's -> 404.
        mockMvc.perform(get("/api/v1/me/children/" + studentA2Id + "/transport").cookie(login(PARENT_A)))
                .andExpect(status().isNotFound());

        // A nonexistent child id -> 404, never a leak.
        mockMvc.perform(get("/api/v1/me/children/" + UUID.randomUUID() + "/transport").cookie(login(PARENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePortalTransportEndpointsAreRoleGated() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/transport").cookie(login(PARENT_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/transport").cookie(login(STUDENT_A)))
                .andExpect(status().isForbidden());
    }
}
