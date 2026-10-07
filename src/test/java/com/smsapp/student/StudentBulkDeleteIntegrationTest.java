package com.smsapp.student;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Student Information -> Bulk Delete: listing candidates per class/section, deleting only students with no
 * dependent history, clearing enquiry links, removing files, auditing, and the STUDENT_DELETE permission gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentBulkDeleteIntegrationTest {

    private static final String ADMIN = "bulkdel-admin@school.example";
    private static final String TEACHER = "bulkdel-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolId;
    private UUID adminUserId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolId = UUID.randomUUID();
        try (Connection connection = superuser(); Statement st = connection.createStatement()) {
            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, students, "
                    + "student_identifications, student_academic_history, student_documents, classes, sections, "
                    + "attendance_records, admission_enquiries, audit_log CASCADE");
            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Bulk Delete School')");

            adminUserId = UUID.randomUUID();
            UUID adminRole = seedUser(st, adminUserId, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, UUID.randomUUID(), TEACHER, "TEACHER");
            UUID permId = UUID.randomUUID();
            st.execute("INSERT INTO permissions (id, name) VALUES ('" + permId + "', 'STUDENT_DELETE')");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('" + adminRole + "', '" + permId + "')");
        }
    }

    private UUID seedUser(Statement st, UUID userId, String email, String role) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return roleId;
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private JsonNode postJson(Cookie cookie, String url, String body) throws Exception {
        var result = mockMvc.perform(post(url).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private UUID createClass(Cookie admin, String name) throws Exception {
        return UUID.fromString(postJson(admin, "/api/v1/classes",
                "{\"schoolId\":\"" + schoolId + "\",\"name\":\"" + name + "\"}").get("id").asText());
    }

    private UUID createSection(Cookie admin, UUID classId, String name) throws Exception {
        return UUID.fromString(postJson(admin, "/api/v1/classes/" + classId + "/sections",
                "{\"name\":\"" + name + "\"}").get("id").asText());
    }

    private UUID createStudent(Cookie admin, UUID sectionId, String firstName, String admissionNumber) throws Exception {
        return UUID.fromString(postJson(admin, "/api/v1/students",
                "{\"schoolId\":\"" + schoolId + "\",\"sectionId\":\"" + sectionId + "\",\"firstName\":\"" + firstName
                        + "\",\"lastName\":\"Test\",\"admissionNumber\":\"" + admissionNumber
                        + "\",\"dateOfBirth\":\"2015-01-01\",\"gender\":\"MALE\"}").get("id").asText());
    }

    @Test
    void deletesOnlyStudentsWithoutHistoryAndCleansUp() throws Exception {
        Cookie admin = login(ADMIN);
        UUID classId = createClass(admin, "Class 3");
        UUID sectionA = createSection(admin, classId, "A");
        UUID sectionB = createSection(admin, classId, "B");
        UUID free = createStudent(admin, sectionA, "Free", "BD-1");
        UUID withAttendance = createStudent(admin, sectionA, "Present", "BD-2");
        UUID inB = createStudent(admin, sectionB, "Bravo", "BD-3");

        Path files = Path.of("target/test-uploads/students/" + free);
        Files.createDirectories(files);
        Files.writeString(files.resolve("note.txt"), "x");

        try (Connection connection = superuser(); Statement st = connection.createStatement()) {
            st.execute("INSERT INTO attendance_records (student_id, date, status, marked_by) VALUES ('"
                    + withAttendance + "', CURRENT_DATE, 'PRESENT', '" + adminUserId + "')");
            st.execute("INSERT INTO admission_enquiries (enquiry_number, applicant_name, converted_student_id) "
                    + "VALUES ('ENQ-BD-1', 'Free Test', '" + free + "')");
        }

        // Whole class: both sections, sorted by section then name; the student with attendance is blocked.
        mockMvc.perform(get("/api/v1/students/bulk-delete/candidates").param("classId", classId.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].fullName").value("Free Test"))
                .andExpect(jsonPath("$[0].className").value("Class 3"))
                .andExpect(jsonPath("$[0].sectionName").value("A"))
                .andExpect(jsonPath("$[0].deletable").value(true))
                .andExpect(jsonPath("$[1].fullName").value("Present Test"))
                .andExpect(jsonPath("$[1].deletable").value(false))
                .andExpect(jsonPath("$[1].blockReason").value("Has attendance -- disable this student instead"))
                .andExpect(jsonPath("$[2].sectionName").value("B"));

        // One section only.
        mockMvc.perform(get("/api/v1/students/bulk-delete/candidates").param("classId", classId.toString())
                        .param("sectionId", sectionB.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inB.toString()));

        UUID missing = UUID.randomUUID();
        JsonNode result = postJson(admin, "/api/v1/students/bulk-delete",
                "{\"studentIds\":[\"" + free + "\",\"" + withAttendance + "\",\"" + missing + "\"]}");
        assertThat(result.get("deleted")).hasSize(1);
        assertThat(result.get("deleted").get(0).get("id").asText()).isEqualTo(free.toString());
        assertThat(result.get("skipped")).hasSize(2);
        assertThat(result.get("skipped").get(0).get("id").asText()).isEqualTo(withAttendance.toString());
        assertThat(result.get("skipped").get(1).get("reason").asText()).contains("not found");

        assertThat(Files.exists(files)).isFalse();
        try (Connection connection = superuser(); Statement st = connection.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM students WHERE id = '" + free + "'")).isZero();
            assertThat(count(st, "SELECT count(*) FROM student_academic_history WHERE student_id = '" + free + "'")).isZero();
            assertThat(count(st, "SELECT count(*) FROM students WHERE id IN ('" + withAttendance + "', '" + inB + "')")).isEqualTo(2);
            assertThat(count(st, "SELECT count(*) FROM admission_enquiries WHERE enquiry_number = 'ENQ-BD-1' "
                    + "AND converted_student_id IS NULL")).isEqualTo(1);
            assertThat(count(st, "SELECT count(*) FROM audit_log WHERE action = 'STUDENT_DELETED' AND entity_id = '"
                    + free + "' AND details->>'admissionNumber' = 'BD-1'")).isEqualTo(1);
        }
    }

    @Test
    void rejectsBadRequests() throws Exception {
        Cookie admin = login(ADMIN);
        UUID classId = createClass(admin, "Class 4");
        UUID otherClass = createClass(admin, "Class 6");
        UUID otherSection = createSection(admin, otherClass, "A");

        mockMvc.perform(post("/api/v1/students/bulk-delete").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/students/bulk-delete/candidates").param("classId", UUID.randomUUID().toString()).cookie(admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/students/bulk-delete/candidates").param("classId", classId.toString())
                        .param("sectionId", otherSection.toString()).cookie(admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresStudentDeletePermission() throws Exception {
        Cookie admin = login(ADMIN);
        UUID classId = createClass(admin, "Class 7");
        UUID student = createStudent(admin, createSection(admin, classId, "A"), "Kept", "BD-9");
        Cookie teacher = login(TEACHER);

        mockMvc.perform(get("/api/v1/students/bulk-delete/candidates").param("classId", classId.toString()).cookie(teacher))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/students/bulk-delete").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentIds\":[\"" + student + "\"]}"))
                .andExpect(status().isForbidden());
    }

    private static long count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
