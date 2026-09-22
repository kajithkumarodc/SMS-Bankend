package com.smsapp.academics;

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

/** Academic years + student promotion against a real PostgreSQL instance. SCHOOL_ADMIN only. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AcademicYearIntegrationTest {

    private static final String ADMIN = "ay-admin@school.example";
    private static final String TEACHER = "ay-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;
    private UUID studentA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolA = UUID.randomUUID();
        studentA = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, students, "
                    + "attendance_records, sections, classes, academic_years CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'School A')");
            UUID adminRoleId = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, TEACHER, "TEACHER");
            st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, status) VALUES ('"
                    + studentA + "', '" + schoolA + "', 'Student A', 'Student', 'A', 'ADM-AY-1', 'ACTIVE')");

            // /students/promote is permission-based (hasAuthority('STUDENT_PROMOTE')), not role-based --
            // self-seed it since the migration-seeded catalog only exists once and this @BeforeEach
            // truncates roles/permissions. Same fix as StudentAdmissionIntegrationTest/RbacIntegrationTest.
            // TEACHER deliberately gets nothing here, so teacherCannotPromoteStudents still gets 403.
            UUID permId = UUID.randomUUID();
            st.execute("INSERT INTO permissions (id, name) VALUES ('" + permId + "', 'STUDENT_PROMOTE')");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('" + adminRoleId + "', '" + permId + "')");
        }
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
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

    private UUID createSection(Cookie admin, String className, String sectionName) throws Exception {
        var classResult = mockMvc.perform(post("/api/v1/classes").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"" + className + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID classId = UUID.fromString(JSON.readTree(classResult.getResponse().getContentAsString()).get("id").asText());

        var sectionResult = mockMvc.perform(post("/api/v1/classes/" + classId + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + sectionName + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JSON.readTree(sectionResult.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Academic years ----------------------------------------------

    @Test
    void schoolAdminCanCreateAndActivateAnAcademicYear() throws Exception {
        Cookie admin = login(ADMIN);

        var created = mockMvc.perform(post("/api/v1/academic-years").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"2025-2026\",\"startDate\":\"2025-06-01\",\"endDate\":\"2026-04-30\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.current").value(false))
                .andReturn();
        UUID yearId = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(patch("/api/v1/academic-years/" + yearId + "/set-current").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true));

        mockMvc.perform(get("/api/v1/academic-years").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("2025-2026"));
    }

    @Test
    void teacherCannotCreateAnAcademicYear() throws Exception {
        mockMvc.perform(post("/api/v1/academic-years").cookie(login(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"2025-2026\",\"startDate\":\"2025-06-01\",\"endDate\":\"2026-04-30\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void endDateNotAfterStartDateReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/academic-years").cookie(login(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad\",\"startDate\":\"2026-04-30\",\"endDate\":\"2025-06-01\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- Promotion ---------------------------------------------------

    @Test
    void schoolAdminCanPromoteAStudentToAnotherSection() throws Exception {
        Cookie admin = login(ADMIN);
        UUID fromSection = createSection(admin, "Grade 5", "A");
        UUID toSection = createSection(admin, "Grade 6", "A");

        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + fromSection + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/students/promote").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSectionId\":\"" + fromSection + "\",\"toSectionId\":\"" + toSection
                                + "\",\"studentIds\":[\"" + studentA + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotedCount").value(1));

        mockMvc.perform(get("/api/v1/students/" + studentA).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionId").value(toSection.toString()));
    }

    @Test
    void promotingFromANonexistentSectionReturns404() throws Exception {
        Cookie admin = login(ADMIN);
        UUID toSection = createSection(admin, "Grade 6", "A");

        mockMvc.perform(post("/api/v1/students/promote").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSectionId\":\"" + UUID.randomUUID() + "\",\"toSectionId\":\"" + toSection
                                + "\",\"studentIds\":[\"" + studentA + "\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherCannotPromoteStudents() throws Exception {
        Cookie admin = login(ADMIN);
        UUID fromSection = createSection(admin, "Grade 5", "A");
        UUID toSection = createSection(admin, "Grade 6", "A");

        mockMvc.perform(post("/api/v1/students/promote").cookie(login(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSectionId\":\"" + fromSection + "\",\"toSectionId\":\"" + toSection
                                + "\",\"studentIds\":[\"" + studentA + "\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void promotingTheSameStudentTwiceForTheSameSessionIsSkippedNotDuplicated() throws Exception {
        Cookie admin = login(ADMIN);
        UUID fromSection = createSection(admin, "Grade 5", "A");
        UUID toSection = createSection(admin, "Grade 6", "A");
        UUID anotherSection = createSection(admin, "Grade 7", "A");
        UUID currentYearId = createAndActivateAcademicYear(admin, "2025-2026");

        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + fromSection + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/students/promote").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSectionId\":\"" + fromSection + "\",\"toSectionId\":\"" + toSection
                                + "\",\"targetAcademicYearId\":\"" + currentYearId
                                + "\",\"studentIds\":[\"" + studentA + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotedCount").value(1));

        // Second attempt for the same student/target session, promoting from where they now actually are.
        mockMvc.perform(post("/api/v1/students/promote").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSectionId\":\"" + toSection + "\",\"toSectionId\":\"" + anotherSection
                                + "\",\"targetAcademicYearId\":\"" + currentYearId
                                + "\",\"studentIds\":[\"" + studentA + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotedCount").value(0))
                .andExpect(jsonPath("$.results[0].promoted").value(false))
                .andExpect(jsonPath("$.results[0].reason").value(org.hamcrest.Matchers.containsString("Already promoted")));

        mockMvc.perform(get("/api/v1/students/" + studentA).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionId").value(toSection.toString()));
    }

    @Test
    void promotionHistoryShowsThePreviousAndNewPlacement() throws Exception {
        Cookie admin = login(ADMIN);
        UUID fromSection = createSection(admin, "Grade 5", "A");
        UUID toSection = createSection(admin, "Grade 6", "A");

        mockMvc.perform(patch("/api/v1/students/" + studentA + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + fromSection + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/students/promote").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromSectionId\":\"" + fromSection + "\",\"toSectionId\":\"" + toSection
                                + "\",\"studentIds\":[\"" + studentA + "\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/academic-years/promotion-history").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].studentId").value(studentA.toString()))
                .andExpect(jsonPath("$.content[0].newSectionId").value(toSection.toString()))
                .andExpect(jsonPath("$.content[0].previousSectionId").value(fromSection.toString()));
    }

    @Test
    void currentAcademicYearEndpointIsReadableByAnyAuthenticatedRole() throws Exception {
        Cookie admin = login(ADMIN);
        UUID yearId = createAndActivateAcademicYear(admin, "2025-2026");

        mockMvc.perform(get("/api/v1/academic-years/current").cookie(login(TEACHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(yearId.toString()));
    }

    private UUID createAndActivateAcademicYear(Cookie admin, String name) throws Exception {
        var created = mockMvc.perform(post("/api/v1/academic-years").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"startDate\":\"2025-06-01\",\"endDate\":\"2026-04-30\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID yearId = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString()).get("id").asText());
        mockMvc.perform(patch("/api/v1/academic-years/" + yearId + "/set-current").cookie(admin))
                .andExpect(status().isOk());
        return yearId;
    }
}
