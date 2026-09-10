package com.smsapp.hostel;

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
 * Hostel blocks + rooms + student allocation against a real PostgreSQL instance
 * with RLS (plan section 2). Covers: tenant isolation on blocks, rooms, the room
 * roster and the portal endpoints; SCHOOL_ADMIN-only create / add / allocate
 * (TEACHER/STUDENT/PARENT 403); a clean 409 on a duplicate room number within a
 * block (and that the same number is fine in another block); a 400 when a room is
 * full; cross-tenant references returning 404 consistently; and ownership
 * isolation on {@code /me/student/hostel} and {@code /me/children/{id}/hostel}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostelIntegrationTest {

    private static final String SCHOOL_A = "hostel-a";
    private static final String SCHOOL_B = "hostel-b";
    private static final String ADMIN_A = "admin@hostel-a.example";
    private static final String TEACHER_A = "teacher@hostel-a.example";
    private static final String STUDENT_A = "student@hostel-a.example";   // Anaya, in room A-101
    private static final String PARENT_A = "parent@hostel-a.example";     // guardian of Anaya
    private static final String PARENT_A2 = "parent2@hostel-a.example";   // guardian of Bhavya (no room)
    private static final String ADMIN_B = "admin@hostel-b.example";
    private static final String STUDENT_B = "student@hostel-b.example";   // Devi, in room B-101
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentAId;    // "Anaya", tenant A, room A-101 (full: capacity 2)
    private UUID studentA2Id;   // "Bhavya", tenant A, no room
    private UUID studentA3Id;   // "Charu", tenant A, room A-101 (Anaya's roommate)
    private UUID studentBId;    // "Devi", tenant B, room B-101
    private UUID blockAId;
    private UUID blockBId;
    private UUID roomA101Id;
    private UUID roomB101Id;

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
        studentA3Id = UUID.randomUUID();
        studentBId = UUID.randomUUID();
        blockAId = UUID.randomUUID();
        blockBId = UUID.randomUUID();
        roomA101Id = UUID.randomUUID();
        roomB101Id = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, announcements, book_loans, library_books, "
                    + "transport_vehicles, transport_routes, hostel_rooms, hostel_blocks, audit_log CASCADE");

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

            seedBlock(st, tenantA, blockAId, "Block A");
            seedBlock(st, tenantB, blockBId, "Block B");
            seedRoom(st, tenantA, roomA101Id, blockAId, "A-101", 2);
            seedRoom(st, tenantB, roomB101Id, blockBId, "B-101", 3);

            seedStudent(st, tenantA, schoolAId, studentAId, "Anaya", studentAUser, parentAUser, roomA101Id);
            seedStudent(st, tenantA, schoolAId, studentA2Id, "Bhavya", null, parentA2User, null);
            seedStudent(st, tenantA, schoolAId, studentA3Id, "Charu", null, null, roomA101Id);
            seedStudent(st, tenantB, schoolBId, studentBId, "Devi", studentBUser, null, roomB101Id);
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

    private static void seedBlock(Statement st, UUID tenantId, UUID id, String name) throws SQLException {
        st.execute("INSERT INTO hostel_blocks (id, tenant_id, name) VALUES ('"
                + id + "', '" + tenantId + "', '" + name + "')");
    }

    private static void seedRoom(Statement st, UUID tenantId, UUID id, UUID blockId, String roomNumber, int capacity)
            throws SQLException {
        st.execute("INSERT INTO hostel_rooms (id, tenant_id, block_id, room_number, capacity) VALUES ('"
                + id + "', '" + tenantId + "', '" + blockId + "', '" + roomNumber + "', " + capacity + ")");
    }

    private static void seedStudent(Statement st, UUID tenantId, UUID schoolId, UUID studentId, String name,
                                    UUID studentUserId, UUID guardianUserId, UUID hostelRoomId) throws SQLException {
        st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status, "
                + "student_user_id, guardian_user_id, hostel_room_id) VALUES ('" + studentId + "', '" + tenantId
                + "', '" + schoolId + "', '" + name + "', 'ADM-" + name + "', 'ACTIVE', "
                + (studentUserId == null ? "NULL" : "'" + studentUserId + "'") + ", "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ", "
                + (hostelRoomId == null ? "NULL" : "'" + hostelRoomId + "'") + ")");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID createBlock(Cookie admin, String name) throws Exception {
        var result = mockMvc.perform(post("/api/v1/hostel/blocks").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID addRoom(Cookie admin, UUID blockId, String roomNumber, int capacity) throws Exception {
        var result = mockMvc.perform(post("/api/v1/hostel/blocks/" + blockId + "/rooms").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomNumber\":\"" + roomNumber + "\",\"capacity\":" + capacity + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Blocks: SCHOOL_ADMIN only + tenant isolation ---------

    @Test
    void schoolAdminCreatesABlockAndListingIsTenantScoped() throws Exception {
        createBlock(login(SCHOOL_A, ADMIN_A), "Block C");

        mockMvc.perform(get("/api/v1/hostel/blocks").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)) // Block A (seeded) + Block C
                .andExpect(jsonPath("$[?(@.name == 'Block B')]").isEmpty());

        mockMvc.perform(get("/api/v1/hostel/blocks").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Block B"));
    }

    @Test
    void teacherStudentAndParentCannotCreateABlock() throws Exception {
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/hostel/blocks").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Rooms: 409 within block, ok in another block, cross-tenant 404 ---

    @Test
    void aDuplicateRoomNumberInABlockIs409ButTheSameNumberInAnotherBlockIsFine() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID blockC = createBlock(adminA, "Block C");

        // "A-101" already exists in Block A.
        mockMvc.perform(post("/api/v1/hostel/blocks/" + blockAId + "/rooms").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomNumber\":\"A-101\",\"capacity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("A-101")));

        // Same number, different block -> fine.
        mockMvc.perform(post("/api/v1/hostel/blocks/" + blockC + "/rooms").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomNumber\":\"A-101\",\"capacity\":2}"))
                .andExpect(status().isCreated());
    }

    @Test
    void addingARoomToAnotherTenantsBlockReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/hostel/blocks/" + blockBId + "/rooms").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomNumber\":\"X-1\",\"capacity\":2}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherCannotAddARoom() throws Exception {
        mockMvc.perform(post("/api/v1/hostel/blocks/" + blockAId + "/rooms").cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomNumber\":\"A-9\",\"capacity\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void roomListShowsLiveOccupancyAndIsBlockScoped() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);

        mockMvc.perform(get("/api/v1/hostel/blocks/" + blockAId + "/rooms").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].roomNumber").value("A-101"))
                .andExpect(jsonPath("$[0].capacity").value(2))
                .andExpect(jsonPath("$[0].occupied").value(2)); // Anaya + Charu

        // Another tenant's block -> 404.
        mockMvc.perform(get("/api/v1/hostel/blocks/" + blockBId + "/rooms").cookie(adminA))
                .andExpect(status().isNotFound());
    }

    // --- Allocation ----------------------------------------

    @Test
    void allocatingToAFullRoomReturns400ThenSucceedsOnceThereIsSpace() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);

        // A-101 is full (capacity 2, Anaya + Charu).
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/hostel-room").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":\"" + roomA101Id + "\"}"))
                .andExpect(status().isBadRequest());

        // A single-bed room -> allocate Bhavya, then a second allocation is rejected.
        UUID single = addRoom(adminA, blockAId, "A-102", 1);
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/hostel-room").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":\"" + single + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostelRoomId").value(single.toString()));

        // A-102 (capacity 1) is now full -> Charu cannot take it -> 400.
        mockMvc.perform(patch("/api/v1/students/" + studentA3Id + "/hostel-room").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":\"" + single + "\"}"))
                .andExpect(status().isBadRequest());

        // Deallocate Bhavya from A-102, then Charu can move in.
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/hostel-room").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostelRoomId").doesNotExist());
        mockMvc.perform(patch("/api/v1/students/" + studentA3Id + "/hostel-room").cookie(adminA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":\"" + single + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void onlySchoolAdminCanAllocateAStudentsRoom() throws Exception {
        String body = "{\"roomId\":null}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(patch("/api/v1/students/" + studentAId + "/hostel-room").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void allocatingAcrossTenantsReturns404Consistently() throws Exception {
        // Tenant A admin, tenant B room.
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/hostel-room").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":\"" + roomB101Id + "\"}"))
                .andExpect(status().isNotFound());

        // Tenant B admin, tenant A student.
        mockMvc.perform(patch("/api/v1/students/" + studentAId + "/hostel-room").cookie(login(SCHOOL_B, ADMIN_B))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":null}"))
                .andExpect(status().isNotFound());
    }

    // --- Room roster (staff) ------------------------------

    @Test
    void roomRosterIsTenantScopedBlockScopedAndStaffOnly() throws Exception {
        mockMvc.perform(get("/api/v1/hostel/blocks/" + blockAId + "/rooms/" + roomA101Id + "/students")
                        .cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].fullName", org.hamcrest.Matchers.containsInAnyOrder("Anaya", "Charu")));

        // Teacher may read it too.
        mockMvc.perform(get("/api/v1/hostel/blocks/" + blockAId + "/rooms/" + roomA101Id + "/students")
                        .cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk());

        // Right room id, wrong block id -> 404.
        mockMvc.perform(get("/api/v1/hostel/blocks/" + UUID.randomUUID() + "/rooms/" + roomA101Id + "/students")
                        .cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isNotFound());

        // Tenant A cannot read a tenant-B room's roster.
        mockMvc.perform(get("/api/v1/hostel/blocks/" + blockBId + "/rooms/" + roomB101Id + "/students")
                        .cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isNotFound());

        // Student / parent may not use the staff endpoint.
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/hostel/blocks/" + blockAId + "/rooms/" + roomA101Id + "/students")
                            .cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Portal: ownership isolation ---------------------

    @Test
    void studentSeesOnlyTheirOwnHostelAllocationWithRoommates() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/hostel").cookie(login(SCHOOL_A, STUDENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockName").value("Block A"))
                .andExpect(jsonPath("$.roomNumber").value("A-101"))
                .andExpect(jsonPath("$.roommates").value(org.hamcrest.Matchers.contains("Charu")));

        mockMvc.perform(get("/api/v1/me/student/hostel").cookie(login(SCHOOL_B, STUDENT_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockName").value("Block B"));
    }

    @Test
    void studentWithNoRoomGetsACleanNotFound() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentAId + "/hostel-room").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":null}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me/student/hostel").cookie(login(SCHOOL_A, STUDENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void parentSeesTheirOwnChildsHostelButNotAnothersOrAnotherTenants() throws Exception {
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/hostel").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomNumber").value("A-101"));

        // Bhavya (studentA2Id) is PARENT_A2's child, not PARENT_A's -> 404.
        mockMvc.perform(get("/api/v1/me/children/" + studentA2Id + "/hostel").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());

        // A tenant-A parent asking for a tenant-B student's id -> 404.
        mockMvc.perform(get("/api/v1/me/children/" + studentBId + "/hostel").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePortalHostelEndpointsAreRoleGated() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/hostel").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/hostel").cookie(login(SCHOOL_A, STUDENT_A)))
                .andExpect(status().isForbidden());
    }
}
