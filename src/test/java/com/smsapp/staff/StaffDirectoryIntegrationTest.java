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

import java.nio.charset.StandardCharsets;
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
 * Human Resource > Staff Directory against a real PostgreSQL instance: adding staff (with their login), the
 * directory filters, editing, the photo and documents, the CSV import, and STAFF_* permission gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffDirectoryIntegrationTest {

    private static final String ADMIN = "sd-admin@school.example";
    private static final String PRINCIPAL = "sd-principal@school.example";
    private static final String TEACHER = "sd-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherRoleId;
    private UUID receptionistRoleId;
    private UUID superAdminRoleId;
    private UUID studentRoleId;
    private UUID facultyId;
    private UUID adminDepartmentId;

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
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, staff_profiles, staff_documents, "
                    + "departments, designations CASCADE");
            facultyId = UUID.randomUUID();
            adminDepartmentId = UUID.randomUUID();
            st.execute("INSERT INTO designations (id, name) VALUES ('" + facultyId + "', 'Faculty')");
            st.execute("INSERT INTO departments (id, name) VALUES ('" + adminDepartmentId + "', 'Admin')");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID principalRole = seedUser(st, PRINCIPAL, "PRINCIPAL");
            teacherRoleId = seedUser(st, TEACHER, "TEACHER");
            receptionistRoleId = insertRole(st, "RECEPTIONIST");
            superAdminRoleId = insertRole(st, "SUPER_ADMIN");
            studentRoleId = insertRole(st, "STUDENT");
            // Same grants V22 makes (other test classes truncate roles/permissions).
            grant(st, adminRole, "STAFF_VIEW", "STAFF_CREATE", "STAFF_EDIT", "STAFF_DELETE", "STAFF_EXPORT");
            grant(st, principalRole, "STAFF_VIEW");
        }
    }

    private UUID insertRole(Statement st, String name) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + name + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = insertRole(st, role);
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
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

    private Cookie login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private Cookie login(String email) throws Exception {
        return login(email, "secret");
    }

    private String staffJson(String staffId, String email, Object... overrides) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("staffId", staffId);
        body.put("roleId", teacherRoleId);
        body.put("designationId", facultyId);
        body.put("departmentId", adminDepartmentId);
        body.put("firstName", "Shivam");
        body.put("lastName", "Verma");
        body.put("email", email);
        body.put("gender", "Male");
        body.put("dateOfBirth", "1990-04-12");
        body.put("dateOfJoining", "2024-06-01");
        body.put("phone", "9552654564");
        body.put("panNumber", "ABCDE1234F");
        body.put("basicSalary", "25000.50");
        body.put("workLocation", "1st Floor");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(body);
    }

    private ResultActions postStaff(Cookie caller, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/staff-members").cookie(caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode createStaff(Cookie caller, String staffId, String email, Object... overrides) throws Exception {
        String response = postStaff(caller, staffJson(staffId, email, overrides)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response);
    }

    @Test
    void optionsListAssignableRolesDesignationsAndDepartments() throws Exception {
        mockMvc.perform(get("/api/v1/staff-members/options").cookie(login(ADMIN)))
                .andExpect(status().isOk())
                // SCHOOL_ADMIN, PRINCIPAL, TEACHER, RECEPTIONIST -- not STUDENT, and not SUPER_ADMIN for an admin.
                .andExpect(jsonPath("$.roles.length()").value(4))
                .andExpect(jsonPath("$.designations[0].name").value("Faculty"))
                .andExpect(jsonPath("$.departments[0].name").value("Admin"));
    }

    @Test
    void addingStaffCreatesTheProfileAndALoginWithATemporaryPassword() throws Exception {
        Cookie admin = login(ADMIN);
        JsonNode created = createStaff(admin, "9002", "Shivam.Verma@School.example");
        JsonNode staff = created.get("staff");
        assertThat(staff.get("fullName").asText()).isEqualTo("Shivam Verma");
        assertThat(staff.get("email").asText()).isEqualTo("shivam.verma@school.example");
        assertThat(staff.get("roleName").asText()).isEqualTo("TEACHER");
        assertThat(staff.get("designationName").asText()).isEqualTo("Faculty");
        assertThat(staff.get("departmentName").asText()).isEqualTo("Admin");
        assertThat(staff.get("gender").asText()).isEqualTo("MALE");
        assertThat(staff.get("basicSalary").decimalValue()).isEqualByComparingTo("25000.50");
        assertThat(staff.get("status").asText()).isEqualTo("ACTIVE");

        // The new staff member can sign in with the temporary password.
        String temporaryPassword = created.get("temporaryPassword").asText();
        assertThat(temporaryPassword).hasSize(12);
        login("shivam.verma@school.example", temporaryPassword);
    }

    @Test
    void addingStaffValidatesTheForm() throws Exception {
        Cookie admin = login(ADMIN);
        postStaff(admin, staffJson(" ", "a@school.example")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "firstName", " ")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "not-an-email")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "gender", "Robot")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "dateOfBirth", null)).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "dateOfBirth", "2999-01-01")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "panNumber", " ")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "phone", "call me")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "basicSalary", "-1")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "casualLeave", -2)).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "contractType", "Forever")).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "roleId", null)).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "roleId", UUID.randomUUID())).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "roleId", studentRoleId)).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "roleId", superAdminRoleId)).andExpect(status().isBadRequest());
        postStaff(admin, staffJson("1", "a@school.example", "departmentId", UUID.randomUUID())).andExpect(status().isNotFound());
        postStaff(admin, staffJson("1", "a@school.example", "designationId", UUID.randomUUID())).andExpect(status().isNotFound());
        // Everything optional can be left out.
        createStaff(admin, "1", "a@school.example", "designationId", null, "departmentId", null, "lastName", null,
                "dateOfJoining", null, "phone", null, "basicSalary", null);
    }

    @Test
    void staffIdAndEmailMustBeUnique() throws Exception {
        Cookie admin = login(ADMIN);
        createStaff(admin, "9002", "one@school.example");
        postStaff(admin, staffJson("9002", "two@school.example")).andExpect(status().isConflict());
        postStaff(admin, staffJson("9003", "ONE@school.example")).andExpect(status().isConflict());
        // A refused add leaves nothing behind -- not even the login.
        mockMvc.perform(get("/api/v1/staff-members").cookie(admin)).andExpect(jsonPath("$.length()").value(1));
        createStaff(admin, "9003", "two@school.example");
    }

    @Test
    void directoryFiltersByRoleAndKeyword() throws Exception {
        Cookie admin = login(ADMIN);
        createStaff(admin, "9002", "shivam@school.example");
        createStaff(admin, "9006", "brandon@school.example", "firstName", "Brandon", "lastName", "Heart",
                "roleId", receptionistRoleId, "workLocation", "2nd Floor", "phone", "34564654");
        Cookie principal = login(PRINCIPAL);

        mockMvc.perform(get("/api/v1/staff-members").cookie(principal))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].staffId").value("9002"))
                .andExpect(jsonPath("$[0].roleName").value("TEACHER"))
                .andExpect(jsonPath("$[0].hasPhoto").value(false));
        mockMvc.perform(get("/api/v1/staff-members").cookie(principal).param("roleId", receptionistRoleId.toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fullName").value("Brandon Heart"));
        mockMvc.perform(get("/api/v1/staff-members").cookie(principal).param("q", "2nd floor"))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/staff-members").cookie(principal).param("q", "9002"))
                .andExpect(jsonPath("$[0].fullName").value("Shivam Verma"));
        mockMvc.perform(get("/api/v1/staff-members").cookie(principal).param("q", "nobody"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/staff-members").cookie(principal).param("status", "INACTIVE"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void editingChangesTheRecordAndRoleButNotTheStaffIdOrEmail() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createStaff(admin, "9002", "shivam@school.example").get("staff").get("id").asText();

        mockMvc.perform(put("/api/v1/staff-members/" + id).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(staffJson("9002", "shivam@school.example", "firstName", "Shiv", "roleId", receptionistRoleId,
                                "departmentId", null, "casualLeave", 12, "bankName", "SBI")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Shiv Verma"))
                .andExpect(jsonPath("$.roleName").value("RECEPTIONIST"))
                .andExpect(jsonPath("$.departmentName").doesNotExist())
                .andExpect(jsonPath("$.casualLeave").value(12))
                .andExpect(jsonPath("$.bankName").value("SBI"));
        mockMvc.perform(put("/api/v1/staff-members/" + id).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(staffJson("9999", "shivam@school.example"))).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/staff-members/" + id).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(staffJson("9002", "other@school.example"))).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/staff-members/" + UUID.randomUUID()).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(staffJson("9002", "shivam@school.example"))).andExpect(status().isNotFound());
    }

    @Test
    void photoAndDocumentsCanBeUploadedReplacedDownloadedAndRemoved() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createStaff(admin, "9002", "shivam@school.example").get("staff").get("id").asText();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/staff-members/" + id + "/photo")
                        .file(new MockMultipartFile("file", "me.png", "image/png", png)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPhoto").value(true));
        mockMvc.perform(get("/api/v1/staff-members/" + id + "/photo").cookie(login(PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));
        // Not an image.
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/staff-members/" + id + "/photo")
                        .file(new MockMultipartFile("file", "cv.pdf", "application/pdf", "%PDF-1.4".getBytes())).cookie(admin))
                .andExpect(status().isBadRequest());

        byte[] first = "%PDF-1.4 resume".getBytes(StandardCharsets.UTF_8);
        byte[] second = "%PDF-1.4 new resume".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/staff-members/" + id + "/documents/resume")
                        .file(new MockMultipartFile("file", "resume.pdf", "application/pdf", first)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].title").value("Resume"));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/staff-members/" + id + "/documents/RESUME")
                        .file(new MockMultipartFile("file", "resume-v2.pdf", "application/pdf", second)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].fileName").value("resume-v2.pdf"));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/staff-members/" + id + "/documents/joining-letter")
                        .file(new MockMultipartFile("file", "letter.pdf", "application/pdf", first)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(2));
        // Photo + joining letter + the replaced resume: the first resume file is gone from disk.
        assertThat(Path.of("target/test-uploads/staff", id).toFile().list()).hasSize(3);
        mockMvc.perform(get("/api/v1/staff-members/" + id + "/documents/resume").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().bytes(second));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/staff-members/" + id + "/documents/passport")
                        .file(new MockMultipartFile("file", "p.pdf", "application/pdf", first)).cookie(admin))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/staff-members/" + id + "/documents/other")
                        .file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", first)).cookie(admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/staff-members/" + id + "/documents/resume").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1));
        mockMvc.perform(get("/api/v1/staff-members/" + id + "/documents/resume").cookie(admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/staff-members/" + id + "/photo").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPhoto").value(false));
        mockMvc.perform(get("/api/v1/staff-members/" + id + "/photo").cookie(admin)).andExpect(status().isNotFound());
        assertThat(Path.of("target/test-uploads/staff", id).toFile().list()).hasSize(1);
    }

    @Test
    void importAddsStaffFromTheSampleFormatAndReportsEachRow() throws Exception {
        Cookie admin = login(ADMIN);
        String header = String.join(",", StaffImportService.COLUMNS);
        // The sample row from Smart School's staff_csvfile.csv (note the stray spaces around some values).
        String sample = "19001,B.Ed.,3 Yrs,Jason ,Sharlton,Max Sharlton,Arya Sharlton,46546654564,5456121565, jason2@gmail.com,"
                + "1980-06-16,Married,2021-06-24,, 83 Evan Street  83 Evan Street Brooklyn,,,Male,,,,,,,,,,,,,,,,,,";
        String csv = header + "\n" + sample + "\n"
                + "19002,,,Mia,,,,,,mia@school.example,1991-02-03,Not Specified,,,,,,Female,,,,,,,30000,EPF1,Probation,Day,Main,,,,,,,\n"
                + "19001,,,Dup Id,,,,,,dupid@school.example,1990-01-01,,,,,,,Male,,,,,,,,,,,,,,,,,,\n"
                + "19003,,,Dup Mail,,,,,,jason2@gmail.com,1990-01-01,,,,,,,Male,,,,,,,,,,,,,,,,,,\n"
                + "19004,,,,,,,,,nobody@school.example,1990-01-01,,,,,,,Male,,,,,,,,,,,,,,,,,,\n"
                + "19005,,,Bad Date,,,,,,baddate@school.example,16/06/1980,,,,,,,Male,,,,,,,,,,,,,,,,,,\n"
                + "19006,,,Bad Optional,,,,12,,badopt@school.example,1990-01-01,Complicated,,,,,,Male,,,,,,,lots,,Forever,,,,,,,,,\n";

        var result = mockMvc.perform(multipart("/api/v1/staff-members/import")
                        .file(new MockMultipartFile("file", "staff.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("roleId", teacherRoleId.toString())
                        .param("designationId", facultyId.toString())
                        .param("departmentId", adminDepartmentId.toString())
                        .cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.skipped").value(4))
                .andReturn();
        JsonNode rows = JSON.readTree(result.getResponse().getContentAsString()).get("rows");
        assertThat(rows.get(0).get("status").asText()).isEqualTo("IMPORTED");
        assertThat(rows.get(0).get("email").asText()).isEqualTo("jason2@gmail.com");
        assertThat(rows.get(0).get("name").asText()).isEqualTo("Jason Sharlton");
        // Each imported staff member can sign in with the password shown in the result.
        login("jason2@gmail.com", rows.get(0).get("temporaryPassword").asText());
        assertThat(rows.get(2).get("messages").get(0).asText()).contains("19001");
        assertThat(rows.get(3).get("messages").get(0).asText()).contains("already exists");
        assertThat(rows.get(4).get("messages").toString()).contains("Name is required");
        assertThat(rows.get(5).get("messages").toString()).contains("not a yyyy-mm-dd date");
        // Bad optional values are left out with a warning; the row still imports.
        assertThat(rows.get(6).get("status").asText()).isEqualTo("IMPORTED");
        assertThat(rows.get(6).get("messages").toString()).contains("Marital status", "Basic salary", "Contract type");

        mockMvc.perform(get("/api/v1/staff-members").cookie(admin).param("q", "19001"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].designationName").value("Faculty"))
                .andExpect(jsonPath("$[0].departmentName").value("Admin"));
        String jasonId = JSON.readTree(mockMvc.perform(get("/api/v1/staff-members").cookie(admin).param("q", "19001"))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asText();
        mockMvc.perform(get("/api/v1/staff-members/" + jasonId).cookie(admin))
                .andExpect(jsonPath("$.qualification").value("B.Ed."))
                .andExpect(jsonPath("$.workExperience").value("3 Yrs"))
                .andExpect(jsonPath("$.maritalStatus").value("MARRIED"))
                .andExpect(jsonPath("$.dateOfJoining").value("2021-06-24"))
                .andExpect(jsonPath("$.currentAddress").value("83 Evan Street  83 Evan Street Brooklyn"));
    }

    @Test
    void importRejectsAFileWithoutTheSampleHeaders() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(multipart("/api/v1/staff-members/import")
                        .file(new MockMultipartFile("file", "staff.csv", "text/csv", "a,b\n1,2\n".getBytes()))
                        .param("roleId", teacherRoleId.toString()).cookie(admin))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/v1/staff-members/import")
                        .file(new MockMultipartFile("file", "staff.csv", "text/csv", new byte[0]))
                        .param("roleId", teacherRoleId.toString()).cookie(admin))
                .andExpect(status().isBadRequest());
        // An unknown role fails the whole import rather than every row.
        String csv = String.join(",", StaffImportService.COLUMNS) + "\n"
                + "1,,,A,,,,,,a@school.example,1990-01-01,,,,,,,Male,,,,,,,,,,,,,,,,,,\n";
        mockMvc.perform(multipart("/api/v1/staff-members/import")
                        .file(new MockMultipartFile("file", "staff.csv", "text/csv", csv.getBytes()))
                        .param("roleId", UUID.randomUUID().toString()).cookie(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sampleFileHasTheImportColumns() throws Exception {
        mockMvc.perform(get("/api/v1/staff-members/import/sample").cookie(login(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("employee_id,qualification,work_exp,name,surname")));
    }

    @Test
    void permissionsFollowTheStaffGrants() throws Exception {
        Cookie principal = login(PRINCIPAL);
        postStaff(principal, staffJson("9002", "x@school.example")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff-members/options").cookie(principal)).andExpect(status().isOk());
        mockMvc.perform(multipart("/api/v1/staff-members/import")
                        .file(new MockMultipartFile("file", "staff.csv", "text/csv", "a".getBytes()))
                        .param("roleId", teacherRoleId.toString()).cookie(principal))
                .andExpect(status().isForbidden());

        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/staff-members").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/staff-members/options").cookie(teacher)).andExpect(status().isForbidden());
    }
}
