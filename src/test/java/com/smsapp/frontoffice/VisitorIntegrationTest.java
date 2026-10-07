package com.smsapp.frontoffice;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Visitor Book against a real PostgreSQL instance: VISITOR_* permission gating, the Add Visitor form's
 * validation, meeting a staff member vs. a student, search/sort, and the attached-document lifecycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VisitorIntegrationTest {

    private static final String ADMIN = "vb-admin@school.example";
    private static final String RECEPTIONIST = "vb-receptionist@school.example";
    private static final String PRINCIPAL = "vb-principal@school.example";
    private static final String TEACHER = "vb-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolPurposeId;
    private UUID principalPurposeId;
    private UUID staffProfileId;
    private UUID studentId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, students, "
                    + "staff_profiles, visitors, front_office_purposes CASCADE");

            UUID schoolId = UUID.randomUUID();
            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Visitor Book School')");
            schoolPurposeId = UUID.randomUUID();
            principalPurposeId = UUID.randomUUID();
            st.execute("INSERT INTO front_office_purposes (id, name) VALUES ('" + schoolPurposeId + "', 'School Events'), ('"
                    + principalPurposeId + "', 'Principal Meeting')");

            UUID adminRole = seedUser(st, ADMIN, "Admin User", "SCHOOL_ADMIN");
            UUID receptionistRole = seedUser(st, RECEPTIONIST, "Front Desk", "RECEPTIONIST");
            UUID principalRole = seedUser(st, PRINCIPAL, "Head Principal", "PRINCIPAL");
            seedUser(st, TEACHER, "Joe Black", "TEACHER");

            // V32's grants only ran at migration time and other test classes truncate
            // roles/permissions, so self-seed exactly what V32 grants.
            grant(st, adminRole, "VISITOR_VIEW", "VISITOR_CREATE", "VISITOR_EDIT", "VISITOR_DELETE",
                    "VISITOR_EXPORT", "VISITOR_PRINT");
            grant(st, receptionistRole, "VISITOR_VIEW", "VISITOR_CREATE", "VISITOR_EDIT");
            grant(st, principalRole, "VISITOR_VIEW", "VISITOR_EXPORT");

            // The teacher has a staff profile -- that's who visitors meet.
            staffProfileId = UUID.randomUUID();
            st.execute("INSERT INTO staff_profiles (id, user_id, employee_code, date_of_joining, salary_amount, status) "
                    + "SELECT '" + staffProfileId + "', id, '9000', '2020-01-01', 1000, 'ACTIVE' FROM users WHERE email = '"
                    + TEACHER + "'");
            studentId = UUID.randomUUID();
            st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, status) "
                    + "VALUES ('" + studentId + "', '" + schoolId + "', 'Edward Thomas', 'Edward', 'Thomas', '18001', 'ACTIVE')");
        }
    }

    private UUID seedUser(Statement st, String email, String fullName, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + fullName + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return roleId;
    }

    private static void grant(Statement st, UUID roleId, String... permissions) throws SQLException {
        for (String name : permissions) {
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + roleId + "', id FROM permissions "
                    + "WHERE name = '" + name + "'");
        }
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    /** A valid Add Visitor form meeting the staff member; {@code overrides} are key/value pairs that replace or add fields. */
    private String visitorJson(String visitorName, Object... overrides) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("purposeId", schoolPurposeId);
        body.put("meetingWithType", "STAFF");
        body.put("staffProfileId", staffProfileId);
        body.put("visitorName", visitorName);
        body.put("phone", "6546546464");
        body.put("idCard", "4545");
        body.put("numberOfPersons", 4);
        body.put("visitDate", "2026-09-26");
        body.put("inTime", "14:17");
        body.put("outTime", "15:17");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(body);
    }

    private UUID createVisitor(Cookie caller, String visitorName, Object... overrides) throws Exception {
        var result = mockMvc.perform(post("/api/v1/visitors").cookie(caller)
                        .contentType(MediaType.APPLICATION_JSON).content(visitorJson(visitorName, overrides)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Create / validation ----------------------------------------------------------

    @Test
    void adminCanLogAVisitorMeetingAStaffMember() throws Exception {
        mockMvc.perform(post("/api/v1/visitors").cookie(login(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(visitorJson("Jhon", "note", "Brought flyers")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purposeName").value("School Events"))
                .andExpect(jsonPath("$.meetingWithType").value("STAFF"))
                .andExpect(jsonPath("$.meetingWithName").value("Joe Black"))
                .andExpect(jsonPath("$.meetingWithCode").value("9000"))
                .andExpect(jsonPath("$.visitorName").value("Jhon"))
                .andExpect(jsonPath("$.numberOfPersons").value(4))
                .andExpect(jsonPath("$.visitDate").value("2026-09-26"))
                .andExpect(jsonPath("$.inTime").value("14:17:00"))
                .andExpect(jsonPath("$.outTime").value("15:17:00"))
                .andExpect(jsonPath("$.note").value("Brought flyers"))
                .andExpect(jsonPath("$.attachment").doesNotExist());
    }

    @Test
    void aVisitorCanMeetAStudentAndTheStaffIdIsIgnored() throws Exception {
        mockMvc.perform(post("/api/v1/visitors").cookie(login(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(visitorJson("Anubhooti", "meetingWithType", "STUDENT", "studentId", studentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meetingWithType").value("STUDENT"))
                .andExpect(jsonPath("$.studentId").value(studentId.toString()))
                .andExpect(jsonPath("$.staffProfileId").doesNotExist())
                .andExpect(jsonPath("$.meetingWithName").value("Edward Thomas"))
                .andExpect(jsonPath("$.meetingWithCode").value("18001"));
    }

    @Test
    void createRejectsInvalidFormsWith400Or404() throws Exception {
        Cookie admin = login(ADMIN);
        String[][] badBodies = {
                {"{\"visitorName\":\"No purpose\"}", "400"},
                {visitorJson("Bad phone", "phone", "call me"), "400"},
                {visitorJson("Out before in", "inTime", "15:00", "outTime", "14:00"), "400"},
                {visitorJson("Nobody to meet", "staffProfileId", null), "400"},
                {visitorJson("Bad type", "meetingWithType", "PARENT"), "400"},
                {visitorJson("Too many", "numberOfPersons", 0), "400"},
                {visitorJson("Unknown purpose", "purposeId", UUID.randomUUID()), "404"},
                {visitorJson("Unknown student", "meetingWithType", "STUDENT", "studentId", UUID.randomUUID()), "404"},
        };
        for (String[] bad : badBodies) {
            mockMvc.perform(post("/api/v1/visitors").cookie(admin).contentType(MediaType.APPLICATION_JSON).content(bad[0]))
                    .andExpect(status().is(Integer.parseInt(bad[1])));
        }
        mockMvc.perform(get("/api/v1/visitors").cookie(admin))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // --- Permissions --------------------------------------------------------------------

    @Test
    void receptionistCanAddAndEditButNotDelete() throws Exception {
        Cookie receptionist = login(RECEPTIONIST);
        UUID id = createVisitor(receptionist, "Herry");

        mockMvc.perform(put("/api/v1/visitors/" + id).cookie(receptionist)
                        .contentType(MediaType.APPLICATION_JSON).content(visitorJson("Herry Updated")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitorName").value("Herry Updated"));
        mockMvc.perform(delete("/api/v1/visitors/" + id).cookie(receptionist))
                .andExpect(status().isForbidden());
    }

    @Test
    void principalCanOnlyViewAndTeacherCannotSeeVisitors() throws Exception {
        createVisitor(login(ADMIN), "Jay");

        Cookie principal = login(PRINCIPAL);
        mockMvc.perform(get("/api/v1/visitors").cookie(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(post("/api/v1/visitors").cookie(principal)
                        .contentType(MediaType.APPLICATION_JSON).content(visitorJson("Nope")))
                .andExpect(status().isForbidden());

        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/visitors").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/visitors/meeting-options").param("type", "STAFF").cookie(teacher))
                .andExpect(status().isForbidden());
    }

    // --- List / search / sort -------------------------------------------------------------

    @Test
    void listSearchesFiltersByDateAndSortsByPurpose() throws Exception {
        Cookie admin = login(ADMIN);
        createVisitor(admin, "Niya Khan", "visitDate", "2026-09-07");
        createVisitor(admin, "Aman", "visitDate", "2026-09-01", "purposeId", principalPurposeId, "phone", "6456345353");

        mockMvc.perform(get("/api/v1/visitors").cookie(admin))
                .andExpect(jsonPath("$.content[0].visitorName").value("Niya Khan")); // latest visit first
        mockMvc.perform(get("/api/v1/visitors").cookie(admin).param("q", "6456345"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].visitorName").value("Aman"));
        mockMvc.perform(get("/api/v1/visitors").cookie(admin).param("from", "2026-09-05").param("to", "2026-09-30"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].visitorName").value("Niya Khan"));
        mockMvc.perform(get("/api/v1/visitors").cookie(admin).param("sort", "purposeName,asc"))
                .andExpect(jsonPath("$.content[0].purposeName").value("Principal Meeting"))
                .andExpect(jsonPath("$.content[1].purposeName").value("School Events"));
        mockMvc.perform(get("/api/v1/visitors").cookie(admin).param("sort", "attachmentStoredFilename,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void meetingOptionsListStaffAndSearchStudents() throws Exception {
        Cookie receptionist = login(RECEPTIONIST);
        mockMvc.perform(get("/api/v1/visitors/meeting-options").param("type", "STAFF").cookie(receptionist))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(staffProfileId.toString()))
                .andExpect(jsonPath("$[0].name").value("Joe Black"))
                .andExpect(jsonPath("$[0].code").value("9000"));
        mockMvc.perform(get("/api/v1/visitors/meeting-options").param("type", "STUDENT").param("q", "1800")
                        .cookie(receptionist))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Edward Thomas"));
        mockMvc.perform(get("/api/v1/visitors/meeting-options").param("type", "STUDENT").param("q", "nobody")
                        .cookie(receptionist))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/visitors/meeting-options").param("type", "PARENT").cookie(receptionist))
                .andExpect(status().isBadRequest());
    }

    // --- Attachment / delete ----------------------------------------------------------------

    @Test
    void attachDownloadReplaceAndRemoveADocument() throws Exception {
        Cookie admin = login(ADMIN);
        UUID id = createVisitor(admin, "Jhon");
        byte[] pdf = "%PDF-1.4 visitor id scan".getBytes();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/visitors/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "id-card.pdf", "application/pdf", pdf)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.fileName").value("id-card.pdf"))
                .andExpect(jsonPath("$.attachment.sizeBytes").value(pdf.length));

        mockMvc.perform(get("/api/v1/visitors/" + id + "/attachment").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("id-card.pdf")))
                .andExpect(content().bytes(pdf));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/visitors/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "script.exe", "application/x-msdownload", pdf)).cookie(admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/visitors/" + id + "/attachment").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment").doesNotExist());
        mockMvc.perform(get("/api/v1/visitors/" + id + "/attachment").cookie(admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAVisitorRemovesItAndItsFile() throws Exception {
        Cookie admin = login(ADMIN);
        UUID id = createVisitor(admin, "Aryman");
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/visitors/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "scan.png", "image/png", new byte[] {1, 2, 3})).cookie(admin))
                .andExpect(status().isOk());
        java.nio.file.Path dir = java.nio.file.Path.of("target/test-uploads/visitors", id.toString());
        org.assertj.core.api.Assertions.assertThat(dir.toFile().list()).hasSize(1);

        mockMvc.perform(delete("/api/v1/visitors/" + id).cookie(admin)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/visitors/" + id).cookie(admin)).andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(dir).doesNotExist();
    }
}
