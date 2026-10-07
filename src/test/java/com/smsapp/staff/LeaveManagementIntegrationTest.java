package com.smsapp.staff;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Human Resource > Approve Leave Request against a real PostgreSQL instance: adding requests (days, half days,
 * overlaps), the approval flow by role (Principal for staff, Super Admin for the Principal, admins for anyone but
 * themselves), notifications, editing, the document, and LEAVE_* permission gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LeaveManagementIntegrationTest {

    private static final String ADMIN = "lm-admin@school.example"; // SCHOOL_ADMIN
    private static final String SUPER_ADMIN = "lm-super@school.example";
    private static final String PRINCIPAL = "lm-principal@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String APPROVAL_PAGE = "/app/human-resource/approve-leave-request";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherRoleId;
    private UUID librarianRoleId;
    private UUID medicalId;
    private UUID casualId;
    private String adminProfileId;
    private String superProfileId;
    private String principalProfileId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, staff_profiles, leave_requests, "
                    + "leave_types, departments, designations, notifications CASCADE");
            medicalId = UUID.randomUUID();
            casualId = UUID.randomUUID();
            st.execute("INSERT INTO leave_types (id, name) VALUES ('" + medicalId + "', 'Medical Leave'), ('" + casualId
                    + "', 'Casual Leave')");
            UUID adminRole = insertRole(st, "SCHOOL_ADMIN");
            UUID superRole = insertRole(st, "SUPER_ADMIN");
            UUID principalRole = insertRole(st, "PRINCIPAL");
            teacherRoleId = insertRole(st, "TEACHER");
            librarianRoleId = insertRole(st, "LIBRARIAN");
            UUID adminUser = seedUser(st, ADMIN, adminRole);
            UUID superUser = seedUser(st, SUPER_ADMIN, superRole);
            UUID principalUser = seedUser(st, PRINCIPAL, principalRole);
            adminProfileId = seedProfile(st, adminUser, "A-1");
            superProfileId = seedProfile(st, superUser, "S-1");
            principalProfileId = seedProfile(st, principalUser, "P-1");
            // Same grants V22 and V48 make (other test classes truncate roles/permissions).
            for (UUID admin : new UUID[] {adminRole, superRole}) {
                grant(st, admin, "STAFF_VIEW", "STAFF_CREATE", "LEAVE_VIEW", "LEAVE_CREATE", "LEAVE_APPROVE");
            }
            grant(st, principalRole, "LEAVE_VIEW", "LEAVE_CREATE", "LEAVE_APPROVE");
            grant(st, teacherRoleId, "LEAVE_VIEW", "LEAVE_CREATE");
            grant(st, librarianRoleId, "LEAVE_VIEW", "LEAVE_CREATE");
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

    /** A staff profile for a seeded login (the directory cannot create Super Admin or admin staff here). */
    private String seedProfile(Statement st, UUID userId, String staffId) throws SQLException {
        UUID profileId = UUID.randomUUID();
        st.execute("INSERT INTO staff_profiles (id, user_id, employee_code, status, first_name, salary_amount) VALUES ('"
                + profileId + "', '" + userId + "', '" + staffId + "', 'ACTIVE', '" + staffId + "', 0)");
        return profileId.toString();
    }

    private static void grant(Statement st, UUID roleId, String... permissions) throws SQLException {
        for (String name : permissions) {
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + roleId + "', id FROM permissions "
                    + "WHERE name = '" + name + "'");
        }
    }

    private Cookie login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private Cookie login(String email) throws Exception {
        return login(email, "secret");
    }

    /** A staff member added through the directory; returns profile id and their login. */
    private record Staff(String profileId, String email, String password) {
        Staff {
            assertThat(profileId).isNotBlank();
        }
    }

    private Staff addStaff(Cookie admin, UUID roleId, String staffId, String name) throws Exception {
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
        JsonNode created = JSON.readTree(response);
        return new Staff(created.get("staff").get("id").asText(), staffId + "@school.example",
                created.get("temporaryPassword").asText());
    }

    private Staff addTeacher(Cookie admin, String staffId, String name) throws Exception {
        return addStaff(admin, teacherRoleId, staffId, name);
    }

    private Cookie loginAs(Staff staff) throws Exception {
        return login(staff.email(), staff.password());
    }

    private String leaveJson(String staffProfileId, String from, String to, Object... overrides) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("staffProfileId", staffProfileId);
        body.put("leaveTypeId", medicalId);
        body.put("applyDate", "2026-10-03");
        body.put("fromDate", from);
        body.put("toDate", to);
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(body);
    }

    private ResultActions postLeave(Cookie caller, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/hr/leave-requests").cookie(caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions putLeave(Cookie caller, String id, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/hr/leave-requests/" + id).cookie(caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String createLeave(Cookie caller, String staffProfileId, String from, String to, Object... overrides) throws Exception {
        String response = postLeave(caller, leaveJson(staffProfileId, from, to, overrides)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("id").asText();
    }

    private JsonNode notifications(Cookie caller) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/v1/notifications").cookie(caller)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // --- Adding and validating -------------------------------------------------------------------------

    @Test
    void optionsListRolesAndLeaveTypes() throws Exception {
        mockMvc.perform(get("/api/v1/hr/leave-requests/options").cookie(login(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(5))
                .andExpect(jsonPath("$.leaveTypes.length()").value(2))
                .andExpect(jsonPath("$.leaveTypes[0].name").value("Casual Leave"));
    }

    @Test
    void daysAreCalendarDaysAndAHalfDayIsHalf() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");

        postLeave(admin, leaveJson(james.profileId(), "2026-10-19", "2026-10-20", "reason", "Family function"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(2.0))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.leaveTypeName").value("Medical Leave"))
                .andExpect(jsonPath("$.staffId").value("9004"))
                .andExpect(jsonPath("$.staffName").value("James"))
                .andExpect(jsonPath("$.roleName").value("TEACHER"))
                .andExpect(jsonPath("$.reason").value("Family function"))
                .andExpect(jsonPath("$.halfDay").doesNotExist());
        postLeave(admin, leaveJson(james.profileId(), "2026-11-02", "2026-11-04")).andExpect(jsonPath("$.days").value(3.0));
        postLeave(admin, leaveJson(james.profileId(), "2026-12-01", "2026-12-01", "halfDay", "second_half"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(0.5))
                .andExpect(jsonPath("$.halfDay").value("SECOND_HALF"));
    }

    @Test
    void addingValidatesTheDatesTypeAndStaff() throws Exception {
        Cookie admin = login(ADMIN);
        String james = addTeacher(admin, "9004", "James").profileId();

        postLeave(admin, leaveJson(james, "2026-10-20", "2026-10-19")).andExpect(status().isBadRequest());
        postLeave(admin, leaveJson(james, "2026-10-19", "2026-10-20", "halfDay", "FIRST_HALF")).andExpect(status().isBadRequest());
        postLeave(admin, leaveJson(james, "2026-10-19", "2026-10-19", "halfDay", "MORNING")).andExpect(status().isBadRequest());
        postLeave(admin, leaveJson(james, "2026-01-01", "2027-12-31")).andExpect(status().isBadRequest());
        postLeave(admin, leaveJson(james, "2026-10-19", "2026-10-20", "status", "Maybe")).andExpect(status().isBadRequest());
        postLeave(admin, leaveJson(james, "2026-10-19", "2026-10-20", "fromDate", null)).andExpect(status().isBadRequest());
        postLeave(admin, leaveJson(james, "2026-10-19", "2026-10-20", "reason", "x".repeat(1001))).andExpect(status().isBadRequest());
        postLeave(admin, leaveJson(james, "2026-10-19", "2026-10-20", "leaveTypeId", UUID.randomUUID())).andExpect(status().isNotFound());
        postLeave(admin, leaveJson(UUID.randomUUID().toString(), "2026-10-19", "2026-10-20")).andExpect(status().isNotFound());
    }

    @Test
    void aStaffMemberCannotHaveOverlappingRequestsExceptDisapprovedOnes() throws Exception {
        Cookie admin = login(ADMIN);
        String james = addTeacher(admin, "9004", "James").profileId();
        String first = createLeave(admin, james, "2026-10-19", "2026-10-21");

        postLeave(admin, leaveJson(james, "2026-10-21", "2026-10-23")).andExpect(status().isConflict());
        postLeave(admin, leaveJson(james, "2026-10-17", "2026-10-19", "halfDay", null)).andExpect(status().isConflict());
        createLeave(admin, james, "2026-10-22", "2026-10-23"); // next to it is fine
        // A disapproved request no longer blocks the dates.
        putLeave(admin, first, leaveJson(james, "2026-10-19", "2026-10-21", "status", "REJECTED")).andExpect(status().isOk());
        createLeave(admin, james, "2026-10-20", "2026-10-20");
        // Editing a request is not blocked by itself.
        putLeave(admin, first, leaveJson(james, "2026-10-19", "2026-10-21", "status", "PENDING")).andExpect(status().isConflict());
    }

    @Test
    void anApproverSetsTheStatusAndTheListShowsLatestLeaveFirst() throws Exception {
        Cookie admin = login(ADMIN);
        String james = addTeacher(admin, "9004", "James").profileId();
        String jason = addTeacher(admin, "90006", "Jason").profileId();
        String older = createLeave(admin, james, "2026-08-26", "2026-08-30");
        String newer = createLeave(admin, jason, "2026-10-14", "2026-10-16", "leaveTypeId", casualId);

        putLeave(admin, newer, leaveJson(jason, "2026-10-14", "2026-10-16", "leaveTypeId", casualId, "status", "approved", "note", "Cover arranged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.note").value("Cover arranged"))
                .andExpect(jsonPath("$.decidedAt").exists());
        putLeave(admin, older, leaveJson(james, "2026-08-26", "2026-08-30", "status", "REJECTED"))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/v1/hr/leave-requests").cookie(login(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].staffName").value("Jason"))
                .andExpect(jsonPath("$[0].leaveTypeName").value("Casual Leave"))
                .andExpect(jsonPath("$[0].days").value(3.0))
                .andExpect(jsonPath("$[1].status").value("REJECTED"));
        // Putting it back to pending clears the decision.
        putLeave(admin, newer, leaveJson(jason, "2026-10-14", "2026-10-16", "leaveTypeId", casualId, "status", "PENDING"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.decidedAt").doesNotExist());
        // The staff member of a request cannot be changed.
        putLeave(admin, newer, leaveJson(james, "2026-10-14", "2026-10-16")).andExpect(status().isBadRequest());
        putLeave(admin, UUID.randomUUID().toString(), leaveJson(james, "2026-10-14", "2026-10-16")).andExpect(status().isNotFound());
    }

    @Test
    void theDocumentCanBeAttachedReplacedDownloadedAndRemovedAndDeletingTheRequestRemovesIt() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createLeave(admin, addTeacher(admin, "9004", "James").profileId(), "2026-10-19", "2026-10-20");
        byte[] first = "%PDF-1.4 certificate".getBytes();
        byte[] second = "%PDF-1.4 new certificate".getBytes();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/hr/leave-requests/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "cert.pdf", "application/pdf", first)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.fileName").value("cert.pdf"));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/hr/leave-requests/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "cert2.pdf", "application/pdf", second)).cookie(admin))
                .andExpect(status().isOk());
        assertThat(Path.of("target/test-uploads/leave-requests", id).toFile().list()).hasSize(1);
        mockMvc.perform(get("/api/v1/hr/leave-requests/" + id + "/attachment").cookie(admin))
                .andExpect(status().isOk()).andExpect(content().bytes(second));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/hr/leave-requests/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", first)).cookie(admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/hr/leave-requests/" + id + "/attachment").cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attachment").doesNotExist());
        mockMvc.perform(get("/api/v1/hr/leave-requests/" + id + "/attachment").cookie(admin)).andExpect(status().isNotFound());

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/hr/leave-requests/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "scan.png", "image/png", new byte[] {1, 2})).cookie(admin))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/hr/leave-requests/" + id).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/hr/leave-requests/" + id).cookie(admin)).andExpect(status().isNotFound());
        assertThat(Path.of("target/test-uploads/leave-requests", id)).doesNotExist();
    }

    // --- The approval flow ---------------------------------------------------------------------------------

    @Test
    void aTeachersRequestGoesToThePrincipalWhoIsNotifiedAndDecidesIt() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        Cookie teacher = loginAs(james);
        Cookie principal = login(PRINCIPAL);

        // Applying: Pending whatever status was sent, and it says who approves it.
        String body = postLeave(teacher, leaveJson(james.profileId(), "2026-10-19", "2026-10-20", "status", "APPROVED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approverLabel").value("Principal"))
                .andExpect(jsonPath("$.canDecide").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = JSON.readTree(body).get("id").asText();

        // Only the Principal is told.
        JsonNode inbox = notifications(principal);
        assertThat(inbox.get("unread").asInt()).isEqualTo(1);
        JsonNode note = inbox.get("items").get(0);
        assertThat(note.get("type").asText()).isEqualTo("LEAVE_REQUESTED");
        assertThat(note.get("title").asText()).isEqualTo("Leave request from James");
        assertThat(note.get("message").asText()).contains("Medical Leave", "10/19/2026 - 10/20/2026", "2 days", "Waiting for your approval");
        assertThat(note.get("link").asText()).isEqualTo(APPROVAL_PAGE);
        assertThat(notifications(login(SUPER_ADMIN)).get("unread").asInt()).isZero();
        assertThat(notifications(login(ADMIN)).get("unread").asInt()).isZero();
        assertThat(notifications(teacher).get("unread").asInt()).isZero();

        // The Principal sees it, can decide it, and approving tells the teacher.
        mockMvc.perform(get("/api/v1/hr/leave-requests/" + id).cookie(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canDecide").value(true));
        putLeave(principal, id, leaveJson(james.profileId(), "2026-10-19", "2026-10-20", "status", "APPROVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        JsonNode decided = notifications(teacher);
        assertThat(decided.get("unread").asInt()).isEqualTo(1);
        assertThat(decided.get("items").get(0).get("title").asText()).isEqualTo("Your leave request was approved");
        assertThat(decided.get("items").get(0).get("message").asText()).contains("Decided by " + PRINCIPAL);

        // The teacher cannot decide their own request (or anyone's).
        putLeave(teacher, id, leaveJson(james.profileId(), "2026-10-19", "2026-10-20", "status", "REJECTED"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/hr/leave-requests/" + id).cookie(teacher)).andExpect(status().isForbidden());
    }

    @Test
    void aLibrariansRequestAlsoGoesToThePrincipalAndDisapprovingNotifiesTheApplicant() throws Exception {
        Cookie admin = login(ADMIN);
        Staff lena = addStaff(admin, librarianRoleId, "9010", "Lena");
        Cookie librarian = loginAs(lena);
        Cookie principal = login(PRINCIPAL);

        String id = createLeave(librarian, lena.profileId(), "2026-11-02", "2026-11-03");
        assertThat(notifications(principal).get("unread").asInt()).isEqualTo(1);

        putLeave(principal, id, leaveJson(lena.profileId(), "2026-11-02", "2026-11-03", "status", "REJECTED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(notifications(librarian).get("items").get(0).get("title").asText())
                .isEqualTo("Your leave request was disapproved");
    }

    @Test
    void aPrincipalsRequestGoesToTheSuperAdminNotThePrincipalThemselves() throws Exception {
        Cookie principal = login(PRINCIPAL);
        Cookie superAdmin = login(SUPER_ADMIN);
        Cookie schoolAdmin = login(ADMIN);

        // The Principal applies for themselves: Pending even if they send Approved, sent to the Super Admin.
        String body = postLeave(principal, leaveJson(principalProfileId, "2026-10-19", "2026-10-20", "status", "APPROVED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approverLabel").value("Super Admin"))
                .andExpect(jsonPath("$.canDecide").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = JSON.readTree(body).get("id").asText();

        JsonNode inbox = notifications(superAdmin);
        assertThat(inbox.get("unread").asInt()).isEqualTo(1);
        assertThat(inbox.get("items").get(0).get("title").asText()).isEqualTo("Leave request from " + PRINCIPAL);
        assertThat(notifications(principal).get("unread").asInt()).isZero();
        assertThat(notifications(schoolAdmin).get("unread").asInt()).isZero(); // the Super Admin exists, so only they are told

        // The Principal cannot approve their own request; the Super Admin and the School Admin can.
        putLeave(principal, id, leaveJson(principalProfileId, "2026-10-19", "2026-10-20", "status", "APPROVED"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/hr/leave-requests/" + id).cookie(superAdmin))
                .andExpect(jsonPath("$.canDecide").value(true));
        putLeave(superAdmin, id, leaveJson(principalProfileId, "2026-10-19", "2026-10-20", "status", "APPROVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(notifications(principal).get("items").get(0).get("title").asText()).isEqualTo("Your leave request was approved");
        putLeave(schoolAdmin, id, leaveJson(principalProfileId, "2026-10-19", "2026-10-20", "status", "REJECTED"))
                .andExpect(status().isOk());
    }

    @Test
    void anAdminsRequestGoesToTheOtherAdminsAndNobodyDecidesTheirOwn() throws Exception {
        Cookie superAdmin = login(SUPER_ADMIN);
        Cookie schoolAdmin = login(ADMIN);
        Cookie principal = login(PRINCIPAL);

        String id = createLeave(superAdmin, superProfileId, "2026-10-19", "2026-10-20");
        assertThat(notifications(schoolAdmin).get("unread").asInt()).isEqualTo(1);
        assertThat(notifications(superAdmin).get("unread").asInt()).isZero();
        assertThat(notifications(principal).get("unread").asInt()).isZero();

        putLeave(superAdmin, id, leaveJson(superProfileId, "2026-10-19", "2026-10-20", "status", "APPROVED"))
                .andExpect(status().isForbidden());
        putLeave(schoolAdmin, id, leaveJson(superProfileId, "2026-10-19", "2026-10-20", "status", "APPROVED"))
                .andExpect(status().isOk());
        // The Principal does not decide an admin's request -- and does not even see it.
        mockMvc.perform(get("/api/v1/hr/leave-requests/" + id).cookie(principal)).andExpect(status().isNotFound());
        putLeave(principal, id, leaveJson(superProfileId, "2026-10-19", "2026-10-20", "status", "REJECTED"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPrincipalSeesOnlyTheRequestsSentToThemAndTheirOwn() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        Staff lena = addStaff(admin, librarianRoleId, "9010", "Lena");
        Cookie principal = login(PRINCIPAL);
        createLeave(admin, james.profileId(), "2026-10-19", "2026-10-20");
        createLeave(admin, lena.profileId(), "2026-10-21", "2026-10-22");
        createLeave(admin, adminProfileId, "2026-10-23", "2026-10-24"); // an admin's own request
        createLeave(admin, superProfileId, "2026-10-25", "2026-10-26"); // the Super Admin's
        createLeave(principal, principalProfileId, "2026-10-27", "2026-10-28"); // their own

        String names = mockMvc.perform(get("/api/v1/hr/leave-requests").cookie(principal)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(names).contains("James", "Lena", PRINCIPAL).doesNotContain(ADMIN).doesNotContain(SUPER_ADMIN);
        // The Super Admin and the School Admin see everything.
        mockMvc.perform(get("/api/v1/hr/leave-requests").cookie(login(SUPER_ADMIN))).andExpect(jsonPath("$.length()").value(5));
        mockMvc.perform(get("/api/v1/hr/leave-requests").cookie(admin)).andExpect(jsonPath("$.length()").value(5));
        // The Principal can add for staff they decide for and for themselves, not for an admin or another Principal.
        mockMvc.perform(get("/api/v1/hr/leave-requests/staff").cookie(principal).param("roleId", teacherRoleId.toString()))
                .andExpect(jsonPath("$.length()").value(1));
        postLeave(principal, leaveJson(adminProfileId, "2026-11-23", "2026-11-24")).andExpect(status().isForbidden());
        postLeave(principal, leaveJson(james.profileId(), "2026-11-02", "2026-11-03", "status", "APPROVED"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void whenThereIsNoPrincipalTheAdminsAreToldInstead() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("UPDATE users SET status = 'INACTIVE' WHERE email = '" + PRINCIPAL + "'");
        }
        createLeave(loginAs(james), james.profileId(), "2026-10-19", "2026-10-20");
        assertThat(notifications(login(SUPER_ADMIN)).get("unread").asInt()).isEqualTo(1);
        assertThat(notifications(admin).get("unread").asInt()).isEqualTo(1);
    }

    // --- Apply Leave (own requests) ----------------------------------------------------------------------------

    private void sql(String statement) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute(statement);
        }
    }

    @Test
    void myInfoShowsWhoApprovesAndWhatIsLeftOfEachLeaveType() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        sql("UPDATE staff_profiles SET medical_leave = 4 WHERE id = '" + james.profileId() + "'");
        Cookie teacher = loginAs(james);
        createLeave(teacher, james.profileId(), "2026-10-19", "2026-10-20");                      // 2 days pending
        String rejected = createLeave(teacher, james.profileId(), "2026-11-02", "2026-11-02");  // 1 day, then disapproved
        putLeave(login(PRINCIPAL), rejected, leaveJson(james.profileId(), "2026-11-02", "2026-11-02", "status", "REJECTED"));
        createLeave(teacher, james.profileId(), "2027-01-05", "2027-01-05");                      // another year

        mockMvc.perform(get("/api/v1/hr/leave-requests/my-info").cookie(teacher).param("year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffId").value("9004"))
                .andExpect(jsonPath("$.roleName").value("TEACHER"))
                .andExpect(jsonPath("$.approverLabel").value("Principal"))
                .andExpect(jsonPath("$.balances.length()").value(2))
                .andExpect(jsonPath("$.balances[1].name").value("Medical Leave"))
                .andExpect(jsonPath("$.balances[1].allotted").value(4))
                .andExpect(jsonPath("$.balances[1].used").value(2.0))
                .andExpect(jsonPath("$.balances[1].available").value(2.0))
                // Casual Leave has no entitlement on the record: no limit.
                .andExpect(jsonPath("$.balances[0].name").value("Casual Leave"))
                .andExpect(jsonPath("$.balances[0].allotted").doesNotExist())
                .andExpect(jsonPath("$.balances[0].available").doesNotExist());
        mockMvc.perform(get("/api/v1/hr/leave-requests/my-info").cookie(login(PRINCIPAL)))
                .andExpect(jsonPath("$.approverLabel").value("Super Admin"));
        mockMvc.perform(get("/api/v1/hr/leave-requests/my-info").cookie(login(SUPER_ADMIN)))
                .andExpect(jsonPath("$.approverLabel").value("Super Admin / School Admin"));
    }

    @Test
    void aUserWithNoStaffProfileHasNoLeaveInfo() throws Exception {
        sql("INSERT INTO users (id, email, password_hash, full_name) VALUES (gen_random_uuid(), 'lm-nobody@school.example', '"
                + passwordEncoder.encode("secret") + "', 'Nobody')");
        sql("INSERT INTO user_roles (user_id, role_id) SELECT u.id, '" + teacherRoleId + "' FROM users u WHERE u.email = 'lm-nobody@school.example'");
        mockMvc.perform(get("/api/v1/hr/leave-requests/my-info").cookie(login("lm-nobody@school.example")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRequestCannotTakeMoreDaysThanTheStaffRecordAllots() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        sql("UPDATE staff_profiles SET medical_leave = 3 WHERE id = '" + james.profileId() + "'");
        Cookie teacher = loginAs(james);

        String first = createLeave(teacher, james.profileId(), "2026-10-19", "2026-10-20"); // 2 of 3 days
        postLeave(teacher, leaveJson(james.profileId(), "2026-11-02", "2026-11-03")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Only 1 day(s) of Medical Leave are available for 2026")));
        postLeave(teacher, leaveJson(james.profileId(), "2026-11-02", "2026-11-02", "halfDay", "FIRST_HALF")).andExpect(status().isCreated()); // 0.5
        postLeave(teacher, leaveJson(james.profileId(), "2026-11-10", "2026-11-10")).andExpect(status().isBadRequest()); // 0.5 left, 1 asked
        // Another leave type with no entitlement, and another year, are not limited.
        postLeave(teacher, leaveJson(james.profileId(), "2026-11-10", "2026-11-14", "leaveTypeId", casualId)).andExpect(status().isCreated());
        postLeave(teacher, leaveJson(james.profileId(), "2027-02-02", "2027-02-04")).andExpect(status().isCreated());
        // Disapproving a request gives its days back -- and an approver editing it is held to the same limit.
        putLeave(login(PRINCIPAL), first, leaveJson(james.profileId(), "2026-10-19", "2026-10-20", "status", "REJECTED")).andExpect(status().isOk());
        postLeave(teacher, leaveJson(james.profileId(), "2026-11-17", "2026-11-18")).andExpect(status().isCreated());
        putLeave(login(PRINCIPAL), first, leaveJson(james.profileId(), "2026-10-19", "2026-10-20", "status", "PENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mineListsOnlyTheCallersOwnRequestsWhateverTheirRole() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        createLeave(admin, james.profileId(), "2026-10-19", "2026-10-20");
        createLeave(admin, adminProfileId, "2026-11-02", "2026-11-02");

        mockMvc.perform(get("/api/v1/hr/leave-requests/mine").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].staffId").value("A-1"));
        mockMvc.perform(get("/api/v1/hr/leave-requests").cookie(admin)).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/hr/leave-requests/mine").cookie(loginAs(james)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].staffId").value("9004"));
    }

    @Test
    void anApplicantCanCancelAPendingRequestButNotADecidedOneOrSomeoneElses() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        Staff jason = addTeacher(admin, "90006", "Jason");
        Cookie teacher = loginAs(james);
        String pending = createLeave(teacher, james.profileId(), "2026-10-19", "2026-10-20");
        String approved = createLeave(teacher, james.profileId(), "2026-11-02", "2026-11-03");
        putLeave(login(PRINCIPAL), approved, leaveJson(james.profileId(), "2026-11-02", "2026-11-03", "status", "APPROVED"));
        String jasonsLeave = createLeave(loginAs(jason), jason.profileId(), "2026-10-19", "2026-10-20");

        mockMvc.perform(post("/api/v1/hr/leave-requests/" + approved + "/cancel").cookie(teacher)).andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/hr/leave-requests/" + jasonsLeave + "/cancel").cookie(teacher)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/hr/leave-requests/" + UUID.randomUUID() + "/cancel").cookie(teacher)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/hr/leave-requests/" + pending + "/cancel").cookie(teacher)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/hr/leave-requests/mine").cookie(teacher)).andExpect(jsonPath("$.length()").value(1));
        // Not even an approver cancels through this: it is the applicant's own withdrawal.
        mockMvc.perform(post("/api/v1/hr/leave-requests/" + jasonsLeave + "/cancel").cookie(login(PRINCIPAL))).andExpect(status().isNotFound());
    }

    // --- Notifications ---------------------------------------------------------------------------------------

    @Test
    void notificationsAreReadOneByOneOrAllAtOnceAndOnlyByTheirOwner() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        Cookie principal = login(PRINCIPAL);
        createLeave(loginAs(james), james.profileId(), "2026-10-19", "2026-10-20");
        createLeave(admin, james.profileId(), "2026-11-02", "2026-11-03");

        JsonNode inbox = notifications(principal);
        // Two Pending requests: one applied by the teacher, one added for them by an admin -- both reach the Principal.
        assertThat(inbox.get("unread").asInt()).isEqualTo(2);
        String id = inbox.get("items").get(0).get("id").asText();
        assertThat(inbox.get("items").get(0).get("read").asBoolean()).isFalse();

        mockMvc.perform(post("/api/v1/notifications/" + id + "/read").cookie(login(SUPER_ADMIN))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/notifications/" + id + "/read").cookie(principal)).andExpect(status().isNoContent());
        assertThat(notifications(principal).get("items").get(0).get("read").asBoolean()).isTrue();
        mockMvc.perform(post("/api/v1/notifications/read-all").cookie(principal)).andExpect(status().isNoContent());
        assertThat(notifications(principal).get("unread").asInt()).isZero();
        mockMvc.perform(post("/api/v1/notifications/" + UUID.randomUUID() + "/read").cookie(principal)).andExpect(status().isNotFound());
    }

    // --- Teachers and permissions --------------------------------------------------------------------------

    @Test
    void aTeacherSeesAndAddsOnlyTheirOwnRequestsAndTheyStayPending() throws Exception {
        Cookie admin = login(ADMIN);
        Staff james = addTeacher(admin, "9004", "James");
        Staff jason = addTeacher(admin, "90006", "Jason");
        String jasonLeave = createLeave(admin, jason.profileId(), "2026-10-14", "2026-10-16");
        Cookie teacher = loginAs(james);

        postLeave(teacher, leaveJson(jason.profileId(), "2026-10-19", "2026-10-20")).andExpect(status().isForbidden());
        postLeave(teacher, leaveJson(james.profileId(), "2026-10-19", "2026-10-20", "status", "APPROVED"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(get("/api/v1/hr/leave-requests").cookie(teacher))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].staffName").value("James"));
        mockMvc.perform(get("/api/v1/hr/leave-requests/" + jasonLeave).cookie(teacher)).andExpect(status().isNotFound());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/hr/leave-requests/" + jasonLeave + "/attachment")
                        .file(new MockMultipartFile("file", "c.pdf", "application/pdf", "%PDF".getBytes())).cookie(teacher))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/hr/leave-requests/staff").cookie(teacher).param("roleId", teacherRoleId.toString()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].staffId").value("9004"));
        mockMvc.perform(get("/api/v1/hr/leave-requests/staff").cookie(admin).param("roleId", teacherRoleId.toString()))
                .andExpect(jsonPath("$.length()").value(2));
        // Without the approve permission there is no editing, deciding or deleting at all.
        mockMvc.perform(delete("/api/v1/hr/leave-requests/" + jasonLeave).cookie(teacher)).andExpect(status().isForbidden());
        putLeave(teacher, jasonLeave, leaveJson(jason.profileId(), "2026-10-14", "2026-10-16", "status", "APPROVED"))
                .andExpect(status().isForbidden());
    }
}
