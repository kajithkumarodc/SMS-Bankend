package com.smsapp.admission;

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

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Online Admissions & Enrollment (plan Phase 4.5) against a real PostgreSQL instance: the public
 * submission/status-lookup surface, admission-cycle open/close rules, the full review workflow and
 * status state machine, and -- the critical integration -- transactional/idempotent approval that
 * creates a real Student + guardian User + portal link.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdmissionIntegrationTest {

    private static final String ADMIN = "adm-admin@school.example";
    private static final String TEACHER = "adm-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolA;
    private UUID academicYearId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
        // The RateLimiter is a singleton bean whose in-memory state outlives any one @Test method
        // (the ApplicationContext -- and this bean -- is cached across the whole class, same as in
        // production across requests). Raise the cap well above what this class's tests submit in
        // total so the *product's* abuse-protection default (5/15min, see application.yml) isn't
        // exercised here; that behavior has its own coverage where it's the thing under test.
        registry.add("ADMISSION_SUBMIT_RATE_LIMIT", () -> "1000");
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolA = UUID.randomUUID();
        academicYearId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, "
                    + "students, academic_years, admission_cycles, admission_applications, "
                    + "admission_application_documents, user_activation_tokens CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Admissions School')");
            st.execute("INSERT INTO academic_years (id, name, start_date, end_date, is_current) VALUES ('"
                    + academicYearId + "', '2026-2027', '2026-06-01', '2027-04-30', true)");

            UUID adminRoleId = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, TEACHER, "TEACHER");
            st.execute("INSERT INTO roles (id, name) VALUES ('" + UUID.randomUUID() + "', 'PARENT')");

            String[] permissions = {
                "ADMISSION_APPLICATION_VIEW", "ADMISSION_APPLICATION_CREATE", "ADMISSION_APPLICATION_EDIT",
                "ADMISSION_APPLICATION_REVIEW", "ADMISSION_APPLICATION_APPROVE", "ADMISSION_APPLICATION_REJECT",
                "ADMISSION_APPLICATION_WAITLIST", "ADMISSION_APPLICATION_DOCUMENT_VIEW",
                "ADMISSION_APPLICATION_DOCUMENT_DOWNLOAD", "ADMISSION_APPLICATION_EXPORT",
                "ADMISSION_CYCLE_VIEW", "ADMISSION_CYCLE_CREATE", "ADMISSION_CYCLE_EDIT",
                "ADMISSION_CYCLE_OPEN", "ADMISSION_CYCLE_CLOSE"
            };
            for (String name : permissions) {
                UUID permId = UUID.randomUUID();
                st.execute("INSERT INTO permissions (id, name) VALUES ('" + permId + "', '" + name + "')");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('"
                        + adminRoleId + "', '" + permId + "')");
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

    private UUID createCycle(Cookie admin) throws Exception {
        var result = mockMvc.perform(post("/api/v1/admission-cycles").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"academicYearId\":\"" + academicYearId + "\","
                                + "\"name\":\"2026-27 Admissions\",\"openDate\":\"2026-01-01\",\"closeDate\":\"2026-12-31\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createAndOpenCycle(Cookie admin) throws Exception {
        UUID cycleId = createCycle(admin);
        mockMvc.perform(post("/api/v1/admission-cycles/" + cycleId + "/open").cookie(admin))
                .andExpect(status().isOk());
        return cycleId;
    }

    private String applicationJson(UUID cycleId, String firstName, String lastName, String guardianEmail) {
        return "{\"admissionCycleId\":\"" + cycleId + "\",\"firstName\":\"" + firstName + "\",\"lastName\":\""
                + lastName + "\",\"dateOfBirth\":\"2015-04-01\",\"gender\":\"MALE\","
                + "\"guardianName\":\"Guardian Of " + firstName + "\",\"guardianRelationship\":\"FATHER\","
                + "\"guardianPhone\":\"+911234500000\",\"guardianEmail\":\"" + guardianEmail + "\","
                + "\"addressLine1\":\"12 Example Street\",\"city\":\"Chennai\",\"state\":\"TN\","
                + "\"country\":\"India\",\"pincode\":\"600001\"}";
    }

    private String submitApplication(UUID cycleId, String firstName, String lastName, String guardianEmail) throws Exception {
        var result = mockMvc.perform(post("/api/v1/public/admissions/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(cycleId, firstName, lastName, guardianEmail)))
                .andExpect(status().isCreated()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("applicationNumber").asText();
    }

    private UUID applicationIdFor(Cookie admin, String applicationNumber) throws Exception {
        var result = mockMvc.perform(get("/api/v1/admission-applications").cookie(admin).param("q", applicationNumber))
                .andExpect(status().isOk()).andReturn();
        JsonNode content = JSON.readTree(result.getResponse().getContentAsString()).get("content");
        return UUID.fromString(content.get(0).get("id").asText());
    }

    // --- Cycles ------------------------------------------------------------

    @Test
    void publicOpenCycleLookupReturns404WhenNoneIsOpen() throws Exception {
        mockMvc.perform(get("/api/v1/public/admissions/schools/" + schoolA + "/open-cycle"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Online admissions are currently closed"));
    }

    @Test
    void publicOpenCycleLookupReturnsTheOpenCycleOnceOpened() throws Exception {
        createAndOpenCycle(login(ADMIN));

        mockMvc.perform(get("/api/v1/public/admissions/schools/" + schoolA + "/open-cycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("2026-27 Admissions"));
    }

    @Test
    void onlyOneCycleCanBeOpenPerSchoolAtATime() throws Exception {
        Cookie admin = login(ADMIN);
        createAndOpenCycle(admin);
        UUID secondCycle = createCycle(admin);

        mockMvc.perform(post("/api/v1/admission-cycles/" + secondCycle + "/open").cookie(admin))
                .andExpect(status().isConflict());
    }

    // --- Public submission ---------------------------------------------

    @Test
    void publicApplicantCanSubmitAgainstTheOpenCycleAndGetsAReferenceNumber() throws Exception {
        UUID cycleId = createAndOpenCycle(login(ADMIN));

        mockMvc.perform(post("/api/v1/public/admissions/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(cycleId, "Alex", "Applicant", "alex.parent@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationNumber").value(matchesPattern("APP-\\d{6}")))
                .andExpect(jsonPath("$.message").value("Application submitted successfully."));
    }

    @Test
    void submittingAgainstAClosedOrUnknownCycleReturns404() throws Exception {
        UUID draftCycle = createCycle(login(ADMIN)); // never opened

        mockMvc.perform(post("/api/v1/public/admissions/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationJson(draftCycle, "Blocked", "Applicant", "blocked@example.com")))
                .andExpect(status().isNotFound());
    }

    // --- Status lookup: safe and identity-checked ------------------------

    @Test
    void statusLookupRequiresTheMatchingEmailAndNeverConfirmsAReferenceOtherwise() throws Exception {
        UUID cycleId = createAndOpenCycle(login(ADMIN));
        String reference = submitApplication(cycleId, "Priya", "Kumar", "priya.parent@example.com");

        mockMvc.perform(post("/api/v1/public/admissions/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationNumber\":\"" + reference + "\",\"email\":\"wrong@example.com\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Application not found"));

        mockMvc.perform(post("/api/v1/public/admissions/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationNumber\":\"" + reference + "\",\"email\":\"priya.parent@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.applicationNumber").value(reference));
    }

    // --- RBAC ----------------------------------------------------------

    @Test
    void teacherCannotAccessAdminAdmissionEndpoints() throws Exception {
        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/admission-applications").cookie(teacher)).andExpect(status().isForbidden());
        // A complete, valid body: @PreAuthorize denial must win over a 400 from Bean Validation, and
        // Spring resolves/validates @RequestBody before the method-security proxy runs, so an invalid
        // body here would 400 regardless of role and prove nothing about authorization.
        mockMvc.perform(post("/api/v1/admission-cycles").cookie(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"academicYearId\":\"" + academicYearId + "\","
                                + "\"name\":\"Blocked Cycle\",\"openDate\":\"2026-01-01\",\"closeDate\":\"2026-12-31\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Review workflow / state machine ---------------------------------

    @Test
    void rejectingRequiresReviewFirstAndBlocksApproval() throws Exception {
        Cookie admin = login(ADMIN);
        UUID cycleId = createAndOpenCycle(admin);
        String reference = submitApplication(cycleId, "Reject", "Case", "reject.parent@example.com");
        UUID appId = applicationIdFor(admin, reference);

        // Cannot reject straight from SUBMITTED -- must move to UNDER_REVIEW first.
        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/reject").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"too soon\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/start-review").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));

        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/reject").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"Seats full\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/approve").cookie(admin))
                .andExpect(status().isConflict());
    }

    @Test
    void waitlistThenReopenAllowsALaterApproval() throws Exception {
        Cookie admin = login(ADMIN);
        UUID cycleId = createAndOpenCycle(admin);
        String reference = submitApplication(cycleId, "Wait", "Listed", "wait.parent@example.com");
        UUID appId = applicationIdFor(admin, reference);

        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/start-review").cookie(admin))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/waitlist").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITLISTED"));
        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/reopen").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/approve").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").exists());
    }

    // --- Approval -> student/guardian/portal conversion --------------------

    @Test
    void approvalCreatesAStudentAndAGuardianPortalAccountAndIsIdempotent() throws Exception {
        Cookie admin = login(ADMIN);
        UUID cycleId = createAndOpenCycle(admin);
        String reference = submitApplication(cycleId, "Nina", "NewAdmit", "nina.parent@example.com");
        UUID appId = applicationIdFor(admin, reference);

        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/start-review").cookie(admin))
                .andExpect(status().isOk());

        var approveResult = mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/approve").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentAdmissionNumber").value(matchesPattern("ADM-\\d{6}")))
                .andExpect(jsonPath("$.guardianUserCreated").value(true))
                .andExpect(jsonPath("$.portalInvitationSent").value(true))
                .andReturn();
        JsonNode approval = JSON.readTree(approveResult.getResponse().getContentAsString());
        String studentId = approval.get("studentId").asText();

        mockMvc.perform(get("/api/v1/students/" + studentId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nina NewAdmit"))
                .andExpect(jsonPath("$.guardianEmail").value("nina.parent@example.com"));

        // Idempotency: a second approve() must not create a second student (plan part 12).
        mockMvc.perform(post("/api/v1/admission-applications/" + appId + "/approve").cookie(admin))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/public/admissions/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationNumber\":\"" + reference + "\",\"email\":\"nina.parent@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void approvalReusesAnExistingGuardianAccountInsteadOfCreatingADuplicate() throws Exception {
        Cookie admin = login(ADMIN);
        UUID cycleId = createAndOpenCycle(admin);
        String sharedEmail = "sibling.parent@example.com";

        String firstReference = submitApplication(cycleId, "First", "Sibling", sharedEmail);
        UUID firstAppId = applicationIdFor(admin, firstReference);
        mockMvc.perform(post("/api/v1/admission-applications/" + firstAppId + "/start-review").cookie(admin));
        var firstApproval = mockMvc.perform(post("/api/v1/admission-applications/" + firstAppId + "/approve").cookie(admin))
                .andExpect(status().isOk()).andReturn();
        String firstGuardianUserId = JSON.readTree(firstApproval.getResponse().getContentAsString())
                .get("guardianUserId").asText();

        String secondReference = submitApplication(cycleId, "Second", "Sibling", sharedEmail);
        UUID secondAppId = applicationIdFor(admin, secondReference);
        mockMvc.perform(post("/api/v1/admission-applications/" + secondAppId + "/start-review").cookie(admin));
        mockMvc.perform(post("/api/v1/admission-applications/" + secondAppId + "/approve").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guardianUserCreated").value(false))
                .andExpect(jsonPath("$.guardianUserId").value(firstGuardianUserId));
    }
}
