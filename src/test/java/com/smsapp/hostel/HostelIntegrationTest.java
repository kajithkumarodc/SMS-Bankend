package com.smsapp.hostel;

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
 * (plan section 2). Covers: SCHOOL_ADMIN-only create / add / allocate
 * (TEACHER/STUDENT/PARENT 403); a clean 409 on a duplicate room number within a
 * block (and that the same number is fine in another block); a 400 when a room
 * is full; nonexistent block/room/student references returning 404
 * consistently; and ownership isolation on {@code /me/student/hostel} and
 * {@code /me/children/{id}/hostel}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostelIntegrationTest {

    private static final String ADMIN_A = "admin@hostel-a.example";
    private static final String TEACHER_A = "teacher@hostel-a.example";
    private static final String STUDENT_A = "student@hostel-a.example";   // Anaya, in room A-101
    private static final String PARENT_A = "parent@hostel-a.example";     // guardian of Anaya
    private static final String PARENT_A2 = "parent2@hostel-a.example";   // guardian of Bhavya (no room)
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentAId;    // "Anaya", room A-101 (full: capacity 2)
    private UUID studentA2Id;   // "Bhavya", no room
    private UUID studentA3Id;   // "Charu", room A-101 (Anaya's roommate)
    private UUID blockAId;
    private UUID roomA101Id;

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
        studentA3Id = UUID.randomUUID();
        blockAId = UUID.randomUUID();
        roomA101Id = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, announcements, book_loans, library_books, "
                    + "transport_vehicles, transport_routes, hostel_rooms, hostel_blocks, audit_log CASCADE");

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

            seedBlock(st, blockAId, "Block A");
            seedRoom(st, roomA101Id, blockAId, "A-101", 2);

            seedStudent(st, schoolAId, studentAId, "Anaya", studentAUser, parentAUser, roomA101Id);
            seedStudent(st, schoolAId, studentA2Id, "Bhavya", null, parentA2User, null);
            seedStudent(st, schoolAId, studentA3Id, "Charu", null, null, roomA101Id);
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

    private static void seedBlock(Statement st, UUID id, String name) throws SQLException {
        st.execute("INSERT INTO hostel_blocks (id, name) VALUES ('" + id + "', '" + name + "')");
    }

    private static void seedRoom(Statement st, UUID id, UUID blockId, String roomNumber, int capacity)
            throws SQLException {
        st.execute("INSERT INTO hostel_rooms (id, block_id, room_number, capacity) VALUES ('"
                + id + "', '" + blockId + "', '" + roomNumber + "', " + capacity + ")");
    }

    private static void seedStudent(Statement st, UUID schoolId, UUID studentId, String name,
                                    UUID studentUserId, UUID guardianUserId, UUID hostelRoomId) throws SQLException {
        st.execute("INSERT INTO students (id, school_id, full_name, admission_number, status, "
                + "student_user_id, guardian_user_id, hostel_room_id) VALUES ('" + studentId
                + "', '" + schoolId + "', '" + name + "', 'ADM-" + name + "', 'ACTIVE', "
                + (studentUserId == null ? "NULL" : "'" + studentUserId + "'") + ", "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ", "
                + (hostelRoomId == null ? "NULL" : "'" + hostelRoomId + "'") + ")");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
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

    // --- Blocks: SCHOOL_ADMIN only ---------

    @Test
    void schoolAdminCreatesABlock() throws Exception {
        createBlock(login(ADMIN_A), "Block C");

        mockMvc.perform(get("/api/v1/hostel/blocks").cookie(login(ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)); // Block A (seeded) + Block C
    }

    @Test
    void teacherStudentAndParentCannotCreateABlock() throws Exception {
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/hostel/blocks").cookie(login(email))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Rooms: 409 within block, ok in another block, existence checks ---

    @Test
    void aDuplicateRoomNumberInABlockIs409ButTheSameNumberInAnotherBlockIsFine() throws Exception {
        Cookie adminA = login(ADMIN_A);
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
    void addingARoomToANonexistentBlockReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/hostel/blocks/" + UUID.randomUUID() + "/rooms").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomNumber\":\"X-1\",\"capacity\":2}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherCannotAddARoom() throws Exception {
        mockMvc.perform(post("/api/v1/hostel/blocks/" + blockAId + "/rooms").cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomNumber\":\"A-9\",\"capacity\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void roomListShowsLiveOccupancy() throws Exception {
        Cookie adminA = login(ADMIN_A);

        mockMvc.perform(get("/api/v1/hostel/blocks/" + blockAId + "/rooms").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].roomNumber").value("A-101"))
                .andExpect(jsonPath("$[0].capacity").value(2))
                .andExpect(jsonPath("$[0].occupied").value(2)); // Anaya + Charu
    }

    @Test
    void roomListForANonexistentBlockReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/hostel/blocks/" + UUID.randomUUID() + "/rooms").cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Allocation ----------------------------------------

    @Test
    void allocatingToAFullRoomReturns400ThenSucceedsOnceThereIsSpace() throws Exception {
        Cookie adminA = login(ADMIN_A);

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
            mockMvc.perform(patch("/api/v1/students/" + studentAId + "/hostel-room").cookie(login(email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void allocatingANonexistentRoomOrStudentReturns404Consistently() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentA2Id + "/hostel-room").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/students/" + UUID.randomUUID() + "/hostel-room").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":null}"))
                .andExpect(status().isNotFound());
    }

    // --- Room roster (staff) ------------------------------

    @Test
    void roomRosterIsBlockScopedAndStaffOnly() throws Exception {
        mockMvc.perform(get("/api/v1/hostel/blocks/" + blockAId + "/rooms/" + roomA101Id + "/students")
                        .cookie(login(ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].fullName", org.hamcrest.Matchers.containsInAnyOrder("Anaya", "Charu")));

        // Teacher may read it too.
        mockMvc.perform(get("/api/v1/hostel/blocks/" + blockAId + "/rooms/" + roomA101Id + "/students")
                        .cookie(login(TEACHER_A)))
                .andExpect(status().isOk());

        // Right room id, wrong block id -> 404.
        mockMvc.perform(get("/api/v1/hostel/blocks/" + UUID.randomUUID() + "/rooms/" + roomA101Id + "/students")
                        .cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());

        // Student / parent may not use the staff endpoint.
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/hostel/blocks/" + blockAId + "/rooms/" + roomA101Id + "/students")
                            .cookie(login(email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Portal: ownership isolation ---------------------

    @Test
    void studentSeesOnlyTheirOwnHostelAllocationWithRoommates() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/hostel").cookie(login(STUDENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockName").value("Block A"))
                .andExpect(jsonPath("$.roomNumber").value("A-101"))
                .andExpect(jsonPath("$.roommates").value(org.hamcrest.Matchers.contains("Charu")));
    }

    @Test
    void studentWithNoRoomGetsACleanNotFound() throws Exception {
        mockMvc.perform(patch("/api/v1/students/" + studentAId + "/hostel-room").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roomId\":null}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me/student/hostel").cookie(login(STUDENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void parentSeesTheirOwnChildsHostelButNotAnothers() throws Exception {
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/hostel").cookie(login(PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomNumber").value("A-101"));

        // Bhavya (studentA2Id) is PARENT_A2's child, not PARENT_A's -> 404.
        mockMvc.perform(get("/api/v1/me/children/" + studentA2Id + "/hostel").cookie(login(PARENT_A)))
                .andExpect(status().isNotFound());

        // A nonexistent child id -> 404.
        mockMvc.perform(get("/api/v1/me/children/" + UUID.randomUUID() + "/hostel").cookie(login(PARENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePortalHostelEndpointsAreRoleGated() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/hostel").cookie(login(PARENT_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/hostel").cookie(login(STUDENT_A)))
                .andExpect(status().isForbidden());
    }
}
