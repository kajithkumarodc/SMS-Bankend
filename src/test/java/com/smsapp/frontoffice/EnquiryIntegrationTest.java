package com.smsapp.frontoffice;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Front Office / Admission Enquiry (Phase 2) against a real PostgreSQL instance:
 * permission-based access (RECEPTIONIST via ENQUIRY_* grants, TEACHER 403), the
 * full enquiry lifecycle, follow-ups, and convert-to-student (including the
 * duplicate-prevention guarantees).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EnquiryIntegrationTest {

    private static final String ADMIN = "fo-admin@school.example";
    private static final String RECEPTIONIST = "fo-receptionist@school.example";
    private static final String TEACHER = "fo-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolA = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            // enquiry_sources is truncated too -- otherwise a source this test class
            // creates (e.g. "Education Fair") leaks into the next run and turns a
            // repeat 201 into a 409. Self-seed the one default row a test relies on,
            // same reasoning as the permissions self-seed below.
            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, "
                    + "students, admission_enquiries, enquiry_follow_ups, enquiry_sources, classes, sections CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Front Office School')");
            st.execute("INSERT INTO enquiry_sources (id, name) VALUES ('" + UUID.randomUUID() + "', 'Website')");

            UUID adminRoleId = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID receptionistRoleId = seedUser(st, RECEPTIONIST, "RECEPTIONIST");
            seedUser(st, TEACHER, "TEACHER");

            // V23's permission seed only runs once at migration time; other test
            // classes' @BeforeEach truncate roles/permissions too (shared test DB),
            // so self-seed exactly what these tests need -- same fix as
            // RbacIntegrationTest (Phase 1).
            String[] enquiryPermissions = {
                "ENQUIRY_VIEW", "ENQUIRY_CREATE", "ENQUIRY_EDIT", "ENQUIRY_DELETE", "ENQUIRY_FOLLOWUP", "ENQUIRY_CONVERT"
            };
            String[] receptionistPermissions = {
                "ENQUIRY_VIEW", "ENQUIRY_CREATE", "ENQUIRY_EDIT", "ENQUIRY_FOLLOWUP", "ENQUIRY_CONVERT"
            };
            for (String name : enquiryPermissions) {
                UUID permId = UUID.randomUUID();
                st.execute("INSERT INTO permissions (id, name) VALUES ('" + permId + "', '" + name + "')");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('"
                        + adminRoleId + "', '" + permId + "')");
                if (java.util.Arrays.asList(receptionistPermissions).contains(name)) {
                    st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('"
                            + receptionistRoleId + "', '" + permId + "')");
                }
            }
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

    private UUID createEnquiry(Cookie caller, String applicantName) throws Exception {
        var result = mockMvc.perform(post("/api/v1/enquiries").cookie(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicantName\":\"" + applicantName + "\",\"guardianName\":\"Guardian G\","
                                + "\"phone\":\"+911234500000\",\"email\":\"lead@example.com\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- RBAC (permission-based, not role-based) ------------------------

    @Test
    void schoolAdminCanCreateAnEnquiryWithAGeneratedNumber() throws Exception {
        mockMvc.perform(post("/api/v1/enquiries").cookie(login(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicantName\":\"Alex Applicant\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enquiryNumber").value(org.hamcrest.Matchers.matchesPattern("ENQ-\\d{6}")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.archived").value(false));
    }

    @Test
    void receptionistCanCreateAndViewEnquiries() throws Exception {
        Cookie receptionist = login(RECEPTIONIST);
        createEnquiry(receptionist, "Reception Lead");

        mockMvc.perform(get("/api/v1/enquiries").cookie(receptionist))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void teacherCannotCreateOrViewEnquiries() throws Exception {
        Cookie teacher = login(TEACHER);
        mockMvc.perform(post("/api/v1/enquiries").cookie(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicantName\":\"Blocked Lead\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/enquiries").cookie(teacher))
                .andExpect(status().isForbidden());
    }

    // --- Search / filters -----------------------------------------------

    @Test
    void searchFiltersByApplicantNameAndStatus() throws Exception {
        Cookie admin = login(ADMIN);
        UUID matching = createEnquiry(admin, "Zoe Zephyr");
        createEnquiry(admin, "Someone Else");
        mockMvc.perform(patch("/api/v1/enquiries/" + matching + "/status").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOST\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/enquiries").cookie(admin).param("q", "zoe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].applicantName").value("Zoe Zephyr"));

        mockMvc.perform(get("/api/v1/enquiries").cookie(admin).param("status", "LOST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(matching.toString()));
    }

    // --- Edit ---------------------------------------------------------

    @Test
    void schoolAdminCanEditAnEnquiry() throws Exception {
        Cookie admin = login(ADMIN);
        UUID id = createEnquiry(admin, "Editable Lead");

        mockMvc.perform(put("/api/v1/enquiries/" + id).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicantName\":\"Edited Lead\",\"guardianName\":\"New Guardian\","
                                + "\"phone\":\"+919999999999\",\"remarks\":\"Updated remarks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicantName").value("Edited Lead"))
                .andExpect(jsonPath("$.remarks").value("Updated remarks"));
    }

    @Test
    void teacherCannotEditAnEnquiry() throws Exception {
        UUID id = createEnquiry(login(ADMIN), "Editable Lead");

        mockMvc.perform(put("/api/v1/enquiries/" + id).cookie(login(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicantName\":\"Hacked Lead\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Follow-ups --------------------------------------------------

    @Test
    void recordingAFollowUpMovesActiveEnquiryToFollowUpStatus() throws Exception {
        Cookie admin = login(ADMIN);
        UUID id = createEnquiry(admin, "Follow Up Lead");

        mockMvc.perform(post("/api/v1/enquiries/" + id + "/follow-ups").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followUpDate\":\"2026-01-10\",\"followUpType\":\"CALL\","
                                + "\"notes\":\"Left voicemail\",\"nextFollowUpDate\":\"2026-01-17\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followUpType").value("CALL"));

        mockMvc.perform(get("/api/v1/enquiries/" + id).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.followUpDate").value("2026-01-17"));

        mockMvc.perform(get("/api/v1/enquiries/" + id + "/follow-ups").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // --- Convert to student ----------------------------------------------

    private String studentAdmissionJson(String admissionNumber) {
        return "{\"schoolId\":\"" + schoolA + "\",\"firstName\":\"Alex\",\"lastName\":\"Applicant\",\"gender\":\"MALE\","
                + "\"dateOfBirth\":\"2015-04-01\",\"admissionNumber\":\"" + admissionNumber + "\"}";
    }

    @Test
    void convertingAnEnquiryCreatesAStudentAndLinksIt() throws Exception {
        Cookie admin = login(ADMIN);
        UUID enquiryId = createEnquiry(admin, "Alex Applicant");

        var result = mockMvc.perform(post("/api/v1/enquiries/" + enquiryId + "/convert").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(studentAdmissionJson("ADM-CONV-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.student.admissionNumber").value("ADM-CONV-1"))
                .andExpect(jsonPath("$.enquiry.status").value("WON"))
                .andReturn();

        String studentId = JSON.readTree(result.getResponse().getContentAsString()).get("student").get("id").asText();
        mockMvc.perform(get("/api/v1/enquiries/" + enquiryId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.convertedStudentId").value(studentId));
    }

    @Test
    void convertingTheSameEnquiryTwiceReturns409AndDoesNotCreateASecondStudent() throws Exception {
        Cookie admin = login(ADMIN);
        UUID enquiryId = createEnquiry(admin, "Alex Applicant");

        mockMvc.perform(post("/api/v1/enquiries/" + enquiryId + "/convert").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(studentAdmissionJson("ADM-CONV-2")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/enquiries/" + enquiryId + "/convert").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(studentAdmissionJson("ADM-CONV-3")))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/students").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void convertingWithADuplicateAdmissionNumberReturns409() throws Exception {
        Cookie admin = login(ADMIN);
        UUID firstEnquiry = createEnquiry(admin, "First Applicant");
        UUID secondEnquiry = createEnquiry(admin, "Second Applicant");

        mockMvc.perform(post("/api/v1/enquiries/" + firstEnquiry + "/convert").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(studentAdmissionJson("ADM-DUP")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/enquiries/" + secondEnquiry + "/convert").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(studentAdmissionJson("ADM-DUP")))
                .andExpect(status().isConflict());
    }

    // --- Archive ----------------------------------------------------

    @Test
    void archivingAnEnquiryHidesItFromTheDefaultListButKeepsTheRecord() throws Exception {
        Cookie admin = login(ADMIN);
        UUID id = createEnquiry(admin, "Archive Me");

        mockMvc.perform(patch("/api/v1/enquiries/" + id + "/archive").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        mockMvc.perform(get("/api/v1/enquiries").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        mockMvc.perform(get("/api/v1/enquiries").cookie(admin).param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(get("/api/v1/enquiries/" + id).cookie(admin))
                .andExpect(status().isOk());
    }

    // --- Summary ------------------------------------------------------

    @Test
    void summaryReflectsRealDatabaseCounts() throws Exception {
        Cookie admin = login(ADMIN);
        createEnquiry(admin, "Lead One");
        UUID lost = createEnquiry(admin, "Lead Two");
        mockMvc.perform(patch("/api/v1/enquiries/" + lost + "/status").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOST\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/enquiries/summary").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEnquiries").value(2))
                .andExpect(jsonPath("$.activeEnquiries").value(1))
                .andExpect(jsonPath("$.lost").value(1))
                .andExpect(jsonPath("$.converted").value(0));
    }

    // --- Enquiry sources ------------------------------------------------

    @Test
    void enquirySourcesAreListedAndConfigurable() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(get("/api/v1/enquiry-sources").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Website')]").exists());

        mockMvc.perform(post("/api/v1/enquiry-sources").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Education Fair\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Education Fair"));
    }
}
