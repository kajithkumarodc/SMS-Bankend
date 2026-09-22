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

/**
 * Teacher Panel (Phase 4 part C) against a real PostgreSQL instance: teacher-to-class/subject
 * assignment (SCHOOL_ADMIN only), and every teacher read scoped to the caller's own assignments --
 * a class-subject not assigned to this teacher, or a student outside their assigned sections, is
 * invisible (empty lists) or 404, never leaked, matching the {@code PortalController} pattern.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherIntegrationTest {

    private static final String ADMIN = "tp-admin@school.example";
    private static final String TEACHER_A = "tp-teacher-a@school.example";
    private static final String TEACHER_B = "tp-teacher-b@school.example";
    private static final String PARENT = "tp-parent@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;
    private UUID teacherAUserId;
    private UUID studentInSection;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolA = UUID.randomUUID();
        studentInSection = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "class_subjects, subjects, sections, classes CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Teacher Panel School')");

            // One row per role name (unique constraint) -- TEACHER_A and TEACHER_B share the TEACHER role id.
            UUID adminRoleId = UUID.randomUUID();
            UUID teacherRoleId = UUID.randomUUID();
            UUID parentRoleId = UUID.randomUUID();
            st.execute("INSERT INTO roles (id, name) VALUES ('" + adminRoleId + "', 'SCHOOL_ADMIN')");
            st.execute("INSERT INTO roles (id, name) VALUES ('" + teacherRoleId + "', 'TEACHER')");
            st.execute("INSERT INTO roles (id, name) VALUES ('" + parentRoleId + "', 'PARENT')");

            seedUser(st, ADMIN, adminRoleId);
            teacherAUserId = seedUser(st, TEACHER_A, teacherRoleId);
            seedUser(st, TEACHER_B, teacherRoleId);
            seedUser(st, PARENT, parentRoleId);
        }
    }

    private UUID seedUser(Statement st, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
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

    private UUID id(String responseBody) throws Exception {
        return UUID.fromString(JSON.readTree(responseBody).get("id").asText());
    }

    /** Creates Grade 5 / Section A / Mathematics, assigns the subject to the class, and puts one active student in the section. */
    private record Setup(UUID classId, UUID sectionId, UUID subjectId) {
    }

    private Setup setUpClassSectionSubjectAndStudent(Cookie admin) throws Exception {
        var classResult = mockMvc.perform(post("/api/v1/classes").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Grade 5\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID classId = id(classResult.getResponse().getContentAsString());

        var sectionResult = mockMvc.perform(post("/api/v1/classes/" + classId + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID sectionId = id(sectionResult.getResponse().getContentAsString());

        var subjectResult = mockMvc.perform(post("/api/v1/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Mathematics\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID subjectId = id(subjectResult.getResponse().getContentAsString());

        mockMvc.perform(post("/api/v1/classes/" + classId + "/subjects").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectId\":\"" + subjectId + "\"}"))
                .andExpect(status().isCreated());

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, "
                    + "status, section_id) VALUES ('" + studentInSection + "', '" + schoolA
                    + "', 'Student One', 'Student', 'One', 'ADM-TP-1', 'ACTIVE', '" + sectionId + "')");
        }

        return new Setup(classId, sectionId, subjectId);
    }

    @Test
    void schoolAdminCanAssignATeacherButAnotherTeacherCannot() throws Exception {
        Cookie admin = login(ADMIN);
        Setup setup = setUpClassSectionSubjectAndStudent(admin);

        mockMvc.perform(patch("/api/v1/classes/" + setup.classId() + "/subjects/" + setup.subjectId() + "/teacher")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"" + teacherAUserId + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/v1/classes/" + setup.classId() + "/subjects/" + setup.subjectId() + "/teacher")
                        .cookie(login(TEACHER_A)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"" + teacherAUserId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assigningATeacherToAnUnlinkedSubjectReturns404() throws Exception {
        Cookie admin = login(ADMIN);
        var classResult = mockMvc.perform(post("/api/v1/classes").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"Grade 6\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID classId = id(classResult.getResponse().getContentAsString());

        mockMvc.perform(patch("/api/v1/classes/" + classId + "/subjects/" + UUID.randomUUID() + "/teacher")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"" + teacherAUserId + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void assignedTeacherSeesTheirClassSectionsSubjectsAndRoster() throws Exception {
        Cookie admin = login(ADMIN);
        Setup setup = setUpClassSectionSubjectAndStudent(admin);
        mockMvc.perform(patch("/api/v1/classes/" + setup.classId() + "/subjects/" + setup.subjectId() + "/teacher")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"" + teacherAUserId + "\"}"))
                .andExpect(status().isNoContent());

        Cookie teacherA = login(TEACHER_A);

        mockMvc.perform(get("/api/v1/teacher/assignments").cookie(teacherA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].className").value("Grade 5"))
                .andExpect(jsonPath("$[0].subjectName").value("Mathematics"))
                .andExpect(jsonPath("$[0].sections[0].name").value("A"));

        mockMvc.perform(get("/api/v1/teacher/students").cookie(teacherA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(studentInSection.toString()));

        mockMvc.perform(get("/api/v1/teacher/students/" + studentInSection).cookie(teacherA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Student One"));
    }

    @Test
    void unassignedTeacherSeesNoAssignmentsOrStudents() throws Exception {
        Cookie admin = login(ADMIN);
        Setup setup = setUpClassSectionSubjectAndStudent(admin);
        mockMvc.perform(patch("/api/v1/classes/" + setup.classId() + "/subjects/" + setup.subjectId() + "/teacher")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"" + teacherAUserId + "\"}"))
                .andExpect(status().isNoContent());

        Cookie teacherB = login(TEACHER_B);

        mockMvc.perform(get("/api/v1/teacher/assignments").cookie(teacherB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/teacher/students").cookie(teacherB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void unassignedTeacherCannotFetchAnotherTeachersStudentDirectlyById() throws Exception {
        Cookie admin = login(ADMIN);
        Setup setup = setUpClassSectionSubjectAndStudent(admin);
        mockMvc.perform(patch("/api/v1/classes/" + setup.classId() + "/subjects/" + setup.subjectId() + "/teacher")
                        .cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"" + teacherAUserId + "\"}"))
                .andExpect(status().isNoContent());

        // Direct ID manipulation: teacher B knows the student's UUID but is not assigned to their section.
        mockMvc.perform(get("/api/v1/teacher/students/" + studentInSection).cookie(login(TEACHER_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonTeacherRoleCannotUseTeacherEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/teacher/assignments").cookie(login(PARENT)))
                .andExpect(status().isForbidden());
    }
}
