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
 * Attendance module against a real PostgreSQL instance: TEACHER may mark
 * (unlike student creation), same-day re-marking is an upsert, future dates
 * are rejected, and a nonexistent student/section reference is reported as
 * 404, never a raw DB error (plan section 2 / 7c).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceIntegrationTest {

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
    private UUID sectionA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID schoolA = UUID.randomUUID();
        studentA = UUID.randomUUID();
        sectionA = UUID.randomUUID();
        UUID classA = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, sections, classes CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Tenant A School')");

            seedUser(st, ADMIN_A, "Admin A", "SCHOOL_ADMIN");
            seedUser(st, TEACHER_A, "Teacher A", "TEACHER");
            seedUser(st, STUDENT_A, "Student User A", "STUDENT");
            seedUser(st, PARENT_A, "Parent User A", "PARENT");

            st.execute("INSERT INTO classes (id, school_id, name) VALUES ('"
                    + classA + "', '" + schoolA + "', 'Grade 5')");
            st.execute("INSERT INTO sections (id, class_id, name) VALUES ('"
                    + sectionA + "', '" + classA + "', 'A')");
            st.execute("INSERT INTO students (id, school_id, section_id, full_name, admission_number, status) "
                    + "VALUES ('" + studentA + "', '" + schoolA + "', '" + sectionA + "', 'Student A', 'ADM-A', 'ACTIVE')");
        }
    }

    private UUID seedUser(Statement st, String email, String fullName, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '" + fullName + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return userId;
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
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
                        .cookie(login(TEACHER_A))
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
                        .cookie(login(ADMIN_A))
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
        Cookie teacher = login(TEACHER_A);

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
                        .cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentA, LocalDate.now().plusDays(1).toString(), "PRESENT")))
                .andExpect(status().isBadRequest());
    }

    // --- Existence checks -------------------------------------------

    @Test
    void cannotMarkAttendanceForANonexistentStudent() throws Exception {
        mockMvc.perform(post("/api/v1/attendance")
                        .cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(UUID.randomUUID(), TODAY, "PRESENT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listByDateReturnsMarkedRecords() throws Exception {
        Cookie adminA = login(ADMIN_A);
        mockMvc.perform(post("/api/v1/attendance").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content(markBody(studentA, TODAY, "PRESENT")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/attendance").param("date", TODAY).cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].studentId").value(studentA.toString()));
    }

    @Test
    void studentHistoryForANonexistentStudentReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/attendance/student/" + UUID.randomUUID())
                        .cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Section roster: GET /api/v1/sections/{id}/students ---------

    @Test
    void sectionRosterListsThatSectionsStudents() throws Exception {
        mockMvc.perform(get("/api/v1/sections/" + sectionA + "/students")
                        .cookie(login(TEACHER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(studentA.toString()));
    }

    @Test
    void sectionRosterForANonexistentSectionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/sections/" + UUID.randomUUID() + "/students")
                        .cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Section attendance: GET /api/v1/attendance?sectionId=&date= -

    @Test
    void attendanceBySectionIsEmptyBeforeMarkingThenReflectsMarks() throws Exception {
        Cookie teacher = login(TEACHER_A);

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
    void attendanceForANonexistentSectionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/attendance").param("sectionId", UUID.randomUUID().toString())
                        .param("date", TODAY)
                        .cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    // --- Role gating: staff-only read endpoints -------------------

    @Test
    void studentsAndParentsCannotReachStaffAttendanceReadEndpoints() throws Exception {
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            Cookie session = login(email);
            // Daily roster for the whole school -- staff only.
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
