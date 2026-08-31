package com.smsapp.attendance;

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
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;

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
    private static final String ADMIN_A = "admin@tenant-a.example";
    private static final String TEACHER_A = "teacher@tenant-a.example";
    private static final String STUDENT_A = "student@tenant-a.example";
    private static final String PARENT_A = "parent@tenant-a.example";
    private static final String TODAY = LocalDate.now().toString();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentA;
    private UUID studentB;
    private UUID sectionA;
    private UUID sectionB;

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
        sectionA = UUID.randomUUID();
        sectionB = UUID.randomUUID();
        UUID classA = UUID.randomUUID();
        UUID classB = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolA);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolB);

            seedUser(st, tenantA, ADMIN_A, "Admin A", "SCHOOL_ADMIN");
            seedUser(st, tenantA, TEACHER_A, "Teacher A", "TEACHER");
            seedUser(st, tenantA, STUDENT_A, "Student User A", "STUDENT");
            seedUser(st, tenantA, PARENT_A, "Parent User A", "PARENT");
            UUID adminB = seedUser(st, tenantB, "admin@tenant-b.example", "Admin B", "SCHOOL_ADMIN");

            seedSection(st, tenantA, schoolA, classA, sectionA, "Grade 5", "A");
            seedSection(st, tenantB, schoolB, classB, sectionB, "Grade 5", "B");

            seedStudent(st, tenantA, schoolA, studentA, sectionA, "Student A", "ADM-A");
            seedStudent(st, tenantB, schoolB, studentB, sectionB, "Student B", "ADM-B");

            // Tenant B already has a record for its own student, on the same date the
            // tests use -- a tenant-A query for that date must never see it.
            st.execute("INSERT INTO attendance_records (id, tenant_id, student_id, date, status, marked_by) VALUES ('"
                    + UUID.randomUUID() + "', '" + tenantB + "', '" + studentB + "', '" + TODAY + "', 'PRESENT', '"
                    + adminB + "')");
        }
    }

    private static void seedSection(Statement st, UUID tenantId, UUID schoolId, UUID classId, UUID sectionId,
                                    String className, String sectionName) throws SQLException {
        st.execute("INSERT INTO classes (id, tenant_id, school_id, name) VALUES ('"
                + classId + "', '" + tenantId + "', '" + schoolId + "', '" + className + "')");
        st.execute("INSERT INTO sections (id, tenant_id, class_id, name) VALUES ('"
                + sectionId + "', '" + tenantId + "', '" + classId + "', '" + sectionName + "')");
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

    private static void seedStudent(Statement st, UUID tenantId, UUID schoolId, UUID studentId, UUID sectionId,
                                    String name, String admissionNumber) throws SQLException {
        st.execute("INSERT INTO students (id, tenant_id, school_id, section_id, full_name, admission_number, status) "
                + "VALUES ('" + studentId + "', '" + tenantId + "', '" + schoolId + "', '" + sectionId + "', '"
                + name + "', '" + admissionNumber + "', 'ACTIVE')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
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
                        .cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.studentId").value(studentA.toString()))
                .andExpect(jsonPath("$.status").value("PRESENT"))
                .andExpect(jsonPath("$.markedBy").isString());
    }

    @Test
    void schoolAdminCanMarkAttendance() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentA, TODAY, "ABSENT")))
                .andExpect(status().isCreated());
    }

    @Test
    void markingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isUnauthorized());
    }

    // --- Upsert --------------------------------------------------------

    @Test
    void reMarkingTheSameStudentAndDateUpdatesInsteadOfDuplicating() throws Exception {
        Cookie teacher = login(SCHOOL_A, TEACHER_A);

        mockMvc.perform(post("/api/v1/attendance").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isCreated());

        // Correction later the same day: not a 409, an update (200).
        mockMvc.perform(post("/api/v1/attendance").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
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
                        .cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentA, LocalDate.now().plusDays(1).toString(), "PRESENT")))
                .andExpect(status().isBadRequest());
    }

    // --- Tenant isolation -------------------------------------------

    @Test
    void cannotMarkAttendanceForAnotherTenantsStudent() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentB, TODAY, "PRESENT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listByDateReturnsOnlyCallersTenantRecords() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        mockMvc.perform(post("/api/v1/attendance").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
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
                        .cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Section roster: GET /api/v1/sections/{id}/students ---------

    @Test
    void sectionRosterListsThatSectionsStudentsTenantScoped() throws Exception {
        mockMvc.perform(get("/api/v1/sections/" + sectionA + "/students")
                        .cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(studentA.toString()));
    }

    @Test
    void sectionRosterForAnotherTenantsSectionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/sections/" + sectionB + "/students")
                        .cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Section attendance: GET /api/v1/attendance?sectionId=&date= -

    @Test
    void attendanceBySectionIsEmptyBeforeMarkingThenReflectsMarks() throws Exception {
        Cookie teacher = login(SCHOOL_A, TEACHER_A);

        // Nothing marked yet -> empty, but 200 (partial roster is normal).
        mockMvc.perform(get("/api/v1/attendance").param("sectionId", sectionA.toString())
                        .param("date", TODAY).cookie(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        mockMvc.perform(post("/api/v1/attendance").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/attendance").param("sectionId", sectionA.toString())
                        .param("date", TODAY).cookie(teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].studentId").value(studentA.toString()))
                .andExpect(jsonPath("$.content[0].status").value("PRESENT"));
    }

    @Test
    void attendanceForAnotherTenantsSectionReturns404() throws Exception {
        // Tenant B has an attendance record for studentB/sectionB TODAY (seeded);
        // tenant A asking by that section id must get 404, not a leak.
        mockMvc.perform(get("/api/v1/attendance").param("sectionId", sectionB.toString())
                        .param("date", TODAY)
                        .cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Role gating: staff-only read endpoints -------------------

    @Test
    void studentsAndParentsCannotReachStaffAttendanceReadEndpoints() throws Exception {
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            Cookie session = login(SCHOOL_A, email);
            // Daily roster for the whole tenant -- staff only.
            mockMvc.perform(get("/api/v1/attendance").param("date", TODAY).cookie(session))
                    .andExpect(status().isForbidden());
            // Section roster's attendance -- staff only.
            mockMvc.perform(get("/api/v1/attendance").param("sectionId", sectionA.toString())
                            .param("date", TODAY).cookie(session))
                    .andExpect(status().isForbidden());
            // One student's history -- staff only; portal uses /me/... .
            mockMvc.perform(get("/api/v1/attendance/student/" + studentA).cookie(session))
                    .andExpect(status().isForbidden());
            // Section student roster -- staff only (marking/gradebook screens).
            mockMvc.perform(get("/api/v1/sections/" + sectionA + "/students").cookie(session))
                    .andExpect(status().isForbidden());
        }
    }
}
