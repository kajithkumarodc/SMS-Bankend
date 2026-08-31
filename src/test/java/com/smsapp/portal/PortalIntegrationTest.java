package com.smsapp.portal;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Portal self-service against a real PostgreSQL instance with RLS enabled. Proves
 * a NEW isolation layer -- OWNERSHIP -- on top of tenant isolation: a student can
 * only reach their own data, a parent only their own children, even within one
 * tenant; and an unlinked account gets a clean 404, not an error.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalIntegrationTest {

    private static final String SCHOOL_A = "portal-a";
    private static final String SCHOOL_B = "portal-b";
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // tenant A logins
    private static final String SU1 = "SU1@portal-a.example";  // STUDENT linked to S1
    private static final String SU2 = "SU2@portal-a.example";  // STUDENT linked to S2
    private static final String GU1 = "GU1@portal-a.example";  // PARENT of S1 + sibling
    private static final String GU2 = "GU2@portal-a.example";  // PARENT of S2
    private static final String LONELY_STUDENT = "nolink-student@portal-a.example";
    private static final String LONELY_PARENT = "nokids-parent@portal-a.example";
    // tenant B logins
    private static final String SU3 = "SU3@portal-b.example";
    private static final String GU3 = "GU3@portal-b.example";

    private UUID s1Id;
    private UUID s2Id;
    private UUID s3Id;

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
        s1Id = UUID.randomUUID();
        UUID s1bId = UUID.randomUUID();
        s2Id = UUID.randomUUID();
        s3Id = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes, audit_log CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolA);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolB);

            // One role row per (tenant, role); a tenant may have many users in a role.
            UUID studentRoleA = seedRole(st, tenantA, "STUDENT");
            UUID parentRoleA = seedRole(st, tenantA, "PARENT");
            UUID studentRoleB = seedRole(st, tenantB, "STUDENT");
            UUID parentRoleB = seedRole(st, tenantB, "PARENT");

            UUID su1Id = seedUser(st, tenantA, SU1, studentRoleA);
            UUID su2Id = seedUser(st, tenantA, SU2, studentRoleA);
            UUID gu1Id = seedUser(st, tenantA, GU1, parentRoleA);
            UUID gu2Id = seedUser(st, tenantA, GU2, parentRoleA);
            seedUser(st, tenantA, LONELY_STUDENT, studentRoleA);
            seedUser(st, tenantA, LONELY_PARENT, parentRoleA);

            UUID su3Id = seedUser(st, tenantB, SU3, studentRoleB);
            UUID gu3Id = seedUser(st, tenantB, GU3, parentRoleB);

            // Tenant A: S1 (Anaya) -> SU1 / GU1 ; sibling (Arjun) -> GU1 ; S2 (Bala) -> SU2 / GU2
            seedStudent(st, tenantA, schoolA, s1Id, "Anaya", su1Id, gu1Id);
            seedStudent(st, tenantA, schoolA, s1bId, "Arjun", null, gu1Id);
            seedStudent(st, tenantA, schoolA, s2Id, "Bala", su2Id, gu2Id);
            // Tenant B: S3 -> SU3 / GU3
            seedStudent(st, tenantB, schoolB, s3Id, "Chetan", su3Id, gu3Id);

            // Attendance: S1 has 2 records, S2 has 1, S3 has 1.
            seedAttendance(st, tenantA, s1Id, "2026-08-24", "PRESENT", su1Id);
            seedAttendance(st, tenantA, s1Id, "2026-08-25", "ABSENT", su1Id);
            seedAttendance(st, tenantA, s2Id, "2026-08-24", "PRESENT", su2Id);
            seedAttendance(st, tenantB, s3Id, "2026-08-24", "LATE", su3Id);
        }
    }

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

    private static void seedStudent(Statement st, UUID tenantId, UUID schoolId, UUID studentId, String name,
                                    UUID studentUserId, UUID guardianUserId) throws SQLException {
        st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status, "
                + "student_user_id, guardian_user_id) VALUES ('" + studentId + "', '" + tenantId + "', '" + schoolId
                + "', '" + name + "', 'ADM-" + name + "', 'ACTIVE', "
                + (studentUserId == null ? "NULL" : "'" + studentUserId + "'") + ", "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ")");
    }

    private static void seedAttendance(Statement st, UUID tenantId, UUID studentId, String date, String attStatus,
                                       UUID markedBy) throws SQLException {
        st.execute("INSERT INTO attendance_records (id, tenant_id, student_id, date, status, marked_by) VALUES ('"
                + UUID.randomUUID() + "', '" + tenantId + "', '" + studentId + "', '" + date + "', '" + attStatus
                + "', '" + markedBy + "')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private JsonNode json(String path, Cookie session) throws Exception {
        var result = mockMvc.perform(get(path).cookie(session)).andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // --- Student self-service ----------------------------------------

    @Test
    void studentSeesOnlyTheirOwnRecord() throws Exception {
        mockMvc.perform(get("/api/v1/me/student").cookie(login(SCHOOL_A, SU1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s1Id.toString()))
                .andExpect(jsonPath("$.fullName").value("Anaya"))
                .andExpect(jsonPath("$.admissionNumber").value("ADM-Anaya"));
    }

    @Test
    void studentAttendanceIsScopedToTheirOwnStudentId() throws Exception {
        // SU1 -> S1's two records; SU2 -> S2's single record.
        JsonNode su1History = json("/api/v1/me/student/attendance", login(SCHOOL_A, SU1));
        assertContentSize(su1History, 2);

        JsonNode su2History = json("/api/v1/me/student/attendance", login(SCHOOL_A, SU2));
        assertContentSize(su2History, 1);
        assertThat(su2History.get("content").get(0).get("status").asText())
                .isEqualTo("PRESENT");
    }

    @Test
    void studentWithNoLinkedRecordGetsCleanNotFound() throws Exception {
        Cookie session = login(SCHOOL_A, LONELY_STUDENT);
        mockMvc.perform(get("/api/v1/me/student").cookie(session)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/me/student/attendance").cookie(session)).andExpect(status().isNotFound());
    }

    // --- Parent self-service ----------------------------------------

    @Test
    void parentSeesAllOfTheirOwnChildrenAndNoOthers() throws Exception {
        JsonNode children = json("/api/v1/me/children", login(SCHOOL_A, GU1));
        assertThat(children).hasSize(2);
        assertThat(children).extracting(n -> n.get("fullName").asText())
                .containsExactly("Anaya", "Arjun"); // ordered by full name
    }

    @Test
    void parentWithNoChildrenGetsAnEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/me/children").cookie(login(SCHOOL_A, LONELY_PARENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void parentCanReadTheirOwnChildsAttendance() throws Exception {
        JsonNode history = json("/api/v1/me/children/" + s1Id + "/attendance", login(SCHOOL_A, GU1));
        assertContentSize(history, 2);
    }

    @Test
    void parentCannotReadAChildThatIsNotTheirsSameTenant() throws Exception {
        // S2 (Bala) belongs to GU2, not GU1 -- same tenant, different parent.
        mockMvc.perform(get("/api/v1/me/children/" + s2Id + "/attendance").cookie(login(SCHOOL_A, GU1)))
                .andExpect(status().isNotFound());
    }

    // --- Tenant isolation on top of ownership -----------------------

    @Test
    void tenantIsolationStillHolds() throws Exception {
        // Tenant B parent cannot reach a Tenant A student, even by exact id.
        mockMvc.perform(get("/api/v1/me/children/" + s1Id + "/attendance").cookie(login(SCHOOL_B, GU3)))
                .andExpect(status().isNotFound());

        // Tenant B student sees their own (Tenant B) record, never Tenant A's.
        mockMvc.perform(get("/api/v1/me/student").cookie(login(SCHOOL_B, SU3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s3Id.toString()))
                .andExpect(jsonPath("$.fullName").value("Chetan"));
    }

    // --- Role gating ----------------------------------------------

    @Test
    void studentEndpointsRejectParentsAndViceVersa() throws Exception {
        mockMvc.perform(get("/api/v1/me/children").cookie(login(SCHOOL_A, SU1)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/student").cookie(login(SCHOOL_A, GU1)))
                .andExpect(status().isForbidden());
    }

    // --- Dashboard integration -----------------------------------

    @Test
    void studentDashboardShowsOwnInfoAndAttendanceSummary() throws Exception {
        JsonNode summary = json("/api/v1/dashboard/summary", login(SCHOOL_A, SU1));
        assertThat(summary.get("placeholder").asBoolean()).isFalse();
        assertThat(summary.get("student").get("fullName").asText()).isEqualTo("Anaya");
        assertThat(summary.get("attendance").get("total").asLong()).isEqualTo(2L);
        assertThat(summary.get("attendance").get("present").asLong()).isEqualTo(1L);
        assertThat(summary.get("attendance").get("absent").asLong()).isEqualTo(1L);
    }

    @Test
    void parentDashboardShowsChildren() throws Exception {
        JsonNode summary = json("/api/v1/dashboard/summary", login(SCHOOL_A, GU1));
        assertThat(summary.get("placeholder").asBoolean()).isFalse();
        assertThat(summary.get("children")).hasSize(2);
    }

    private static void assertContentSize(JsonNode pagedModel, int expected) {
        assertThat(pagedModel.get("content")).hasSize(expected);
    }
}
