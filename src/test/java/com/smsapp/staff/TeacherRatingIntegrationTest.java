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
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Human Resource > Teachers Rating against a real PostgreSQL instance: students rate teachers, the school approves or
 * deletes ratings, only approved ratings count toward the average, and permission gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherRatingIntegrationTest {

    private static final String ADMIN = "tr-admin@school.example";
    private static final String PRINCIPAL = "tr-principal@school.example";
    private static final String TEACHER = "tr-teacher@school.example";
    private static final String STUDENT = "tr-student@school.example";
    private static final String STUDENT_NOT_LINKED = "tr-unlinked@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherRoleId;
    private UUID librarianRoleId;
    private UUID firstStudentId;
    private UUID secondStudentId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
    }

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, students, staff_profiles, "
                    + "teacher_ratings, departments, designations CASCADE");
            UUID school = UUID.randomUUID();
            st.execute("INSERT INTO schools (id, name) VALUES ('" + school + "', 'Rating School')");
            UUID adminRole = insertRole(st, "SCHOOL_ADMIN");
            UUID principalRole = insertRole(st, "PRINCIPAL");
            teacherRoleId = insertRole(st, "TEACHER");
            librarianRoleId = insertRole(st, "LIBRARIAN");
            UUID studentRole = insertRole(st, "STUDENT");
            seedUser(st, ADMIN, adminRole);
            seedUser(st, PRINCIPAL, principalRole);
            seedUser(st, TEACHER, teacherRoleId);
            UUID studentUser = seedUser(st, STUDENT, studentRole);
            seedUser(st, STUDENT_NOT_LINKED, studentRole);
            firstStudentId = UUID.randomUUID();
            secondStudentId = UUID.randomUUID();
            seedStudent(st, school, firstStudentId, "Saurabh", studentUser);
            seedStudent(st, school, secondStudentId, "Glen", null);
            // Same grants V22/V50 make (other test classes truncate roles/permissions).
            grant(st, adminRole, "STAFF_VIEW", "STAFF_CREATE", "TEACHER_RATING_VIEW", "TEACHER_RATING_MANAGE");
            grant(st, principalRole, "TEACHER_RATING_VIEW", "TEACHER_RATING_MANAGE");
        }
    }

    private UUID insertRole(Statement st, String name) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + name + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return userId;
    }

    private static void seedStudent(Statement st, UUID schoolId, UUID studentId, String name, UUID studentUserId) throws SQLException {
        st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, status, student_user_id) "
                + "VALUES ('" + studentId + "', '" + schoolId + "', '" + name + " Shah', '" + name + "', 'Shah', 'ADM-" + name
                + "', 'ACTIVE', " + (studentUserId == null ? "NULL" : "'" + studentUserId + "'") + ")");
    }

    private static void grant(Statement st, UUID roleId, String... permissions) throws SQLException {
        for (String name : permissions) {
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + roleId + "', id FROM permissions "
                    + "WHERE name = '" + name + "'");
        }
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String addStaff(Cookie admin, UUID roleId, String staffId, String name) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("staffId", staffId);
        body.put("roleId", roleId);
        body.put("firstName", name);
        body.put("email", staffId + "@school.example");
        body.put("gender", "Male");
        body.put("dateOfBirth", "1990-04-12");
        body.put("panNumber", "ABCDE1234F");
        String response = mockMvc.perform(post("/api/v1/staff-members").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("staff").get("id").asText();
    }

    private ResultActions rate(Cookie student, String staffProfileId, Object stars, String comment) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("staffProfileId", staffProfileId);
        body.put("rating", stars);
        body.put("comment", comment);
        return mockMvc.perform(post("/api/v1/me/teacher-ratings").cookie(student).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }

    /** Adds a rating as the student, on behalf of a given student row (the second student has no login, so SQL). */
    private void insertRating(String staffProfileId, UUID studentId, int stars, String status, String comment) throws SQLException {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("INSERT INTO teacher_ratings (staff_profile_id, student_id, rating, comment, status) VALUES ('" + staffProfileId
                    + "', '" + studentId + "', " + stars + ", '" + comment + "', '" + status + "')");
        }
    }

    @Test
    void aStudentSeesOnlyTeachersAndRatesOneOnceAsPending() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, teacherRoleId, "9002", "Shivam");
        addStaff(admin, librarianRoleId, "9010", "Lena");
        Cookie student = login(STUDENT);

        mockMvc.perform(get("/api/v1/me/teacher-ratings").cookie(student))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].staffId").value("9002"))
                .andExpect(jsonPath("$[0].myRating").doesNotExist());

        rate(student, shivam, 4, "no comment").andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/me/teacher-ratings").cookie(student))
                .andExpect(jsonPath("$[0].myRating").value(4))
                .andExpect(jsonPath("$[0].myComment").value("no comment"))
                .andExpect(jsonPath("$[0].myStatus").value("PENDING"));
        rate(student, shivam, 5, "again").andExpect(status().isConflict());
    }

    @Test
    void ratingsAreValidated() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, teacherRoleId, "9002", "Shivam");
        String lena = addStaff(admin, librarianRoleId, "9010", "Lena");
        Cookie student = login(STUDENT);

        rate(student, shivam, 0, null).andExpect(status().isBadRequest());
        rate(student, shivam, 6, null).andExpect(status().isBadRequest());
        rate(student, shivam, null, null).andExpect(status().isBadRequest());
        rate(student, shivam, 3, "x".repeat(1001)).andExpect(status().isBadRequest());
        rate(student, lena, 3, null).andExpect(status().isBadRequest()); // not a teacher
        rate(student, UUID.randomUUID().toString(), 3, null).andExpect(status().isNotFound());
        // Only a student with a linked student record can rate.
        rate(login(STUDENT_NOT_LINKED), shivam, 3, null).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/me/teacher-ratings").cookie(login(STUDENT_NOT_LINKED))).andExpect(status().isNotFound());
        // Staff and school roles cannot rate.
        rate(admin, shivam, 3, null).andExpect(status().isForbidden());
    }

    @Test
    void theSchoolListsApprovesAndDeletesRatingsAndOnlyApprovedOnesCount() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, teacherRoleId, "9002", "Shivam");
        insertRating(shivam, firstStudentId, 5, "APPROVED", "Excellent");
        insertRating(shivam, secondStudentId, 3, "PENDING", "good");

        mockMvc.perform(get("/api/v1/teacher-ratings").cookie(login(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].staffId").value("9002"))
                .andExpect(jsonPath("$[0].staffName").value("Shivam"))
                .andExpect(jsonPath("$[0].studentName").value(org.hamcrest.Matchers.endsWith("Shah")));
        mockMvc.perform(get("/api/v1/teacher-ratings").cookie(admin).param("status", "pending"))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].comment").value("good"));
        // Only the approved 5 counts so far.
        mockMvc.perform(get("/api/v1/teacher-ratings/summary/" + shivam).cookie(admin))
                .andExpect(jsonPath("$.average").value(5.0)).andExpect(jsonPath("$.count").value(1));

        String pendingId = JSON.readTree(mockMvc.perform(get("/api/v1/teacher-ratings").cookie(admin).param("status", "PENDING"))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asText();
        mockMvc.perform(post("/api/v1/teacher-ratings/" + pendingId + "/approve").cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/teacher-ratings/" + pendingId + "/approve").cookie(admin)).andExpect(status().isNoContent()); // again: fine
        mockMvc.perform(get("/api/v1/teacher-ratings/summary/" + shivam).cookie(admin))
                .andExpect(jsonPath("$.average").value(4.0)).andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(delete("/api/v1/teacher-ratings/" + pendingId).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/teacher-ratings/" + pendingId).cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/teacher-ratings/" + UUID.randomUUID() + "/approve").cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/teacher-ratings/summary/" + shivam).cookie(admin))
                .andExpect(jsonPath("$.average").value(5.0)).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void theAverageHasOneDecimalAndIsEmptyWithoutApprovedRatings() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, teacherRoleId, "9002", "Shivam");
        mockMvc.perform(get("/api/v1/teacher-ratings/summary/" + shivam).cookie(admin))
                .andExpect(jsonPath("$.average").doesNotExist()).andExpect(jsonPath("$.count").value(0));
        insertRating(shivam, firstStudentId, 5, "APPROVED", "a");
        insertRating(shivam, secondStudentId, 4, "APPROVED", "b");
        // A third approved rating from a third student: 5 + 4 + 4 = 13 / 3 = 4.3 (as on the profile page).
        UUID third = UUID.randomUUID();
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, status) "
                    + "SELECT '" + third + "', school_id, 'Third Shah', 'Third', 'Shah', 'ADM-Third', 'ACTIVE' FROM students LIMIT 1");
        }
        insertRating(shivam, third, 4, "APPROVED", "c");
        JsonNode summary = JSON.readTree(mockMvc.perform(get("/api/v1/teacher-ratings/summary/" + shivam).cookie(admin))
                .andReturn().getResponse().getContentAsString());
        assertThat(summary.get("average").decimalValue()).isEqualByComparingTo("4.3");
        assertThat(summary.get("count").asInt()).isEqualTo(3);
        mockMvc.perform(get("/api/v1/teacher-ratings/summary/" + UUID.randomUUID()).cookie(admin)).andExpect(status().isNotFound());
    }

    @Test
    void permissionsFollowTheRatingGrants() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, teacherRoleId, "9002", "Shivam");
        insertRating(shivam, firstStudentId, 5, "PENDING", "x");
        String id = JSON.readTree(mockMvc.perform(get("/api/v1/teacher-ratings").cookie(admin)).andReturn().getResponse()
                .getContentAsString()).get(0).get("id").asText();

        Cookie teacher = login(TEACHER);
        Cookie student = login(STUDENT);
        mockMvc.perform(get("/api/v1/teacher-ratings").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/teacher-ratings").cookie(student)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/teacher-ratings/" + id + "/approve").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/teacher-ratings/" + id).cookie(student)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/teacher-ratings/summary/" + shivam).cookie(student)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/teacher-ratings").cookie(teacher)).andExpect(status().isForbidden());
    }
}
