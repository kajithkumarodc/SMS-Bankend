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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3: the full admission-form field set, search/filters, siblings, academic history, identification
 * documents, and file uploads -- all against a real PostgreSQL instance and the real local filesystem
 * (isolated under {@code app.storage.base-dir} = {@code ./target/test-uploads}, see application-test.yml).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudentAdmissionIntegrationTest {

    private static final String ADMIN = "adm3-admin@school.example";
    private static final String TEACHER = "adm3-teacher@school.example";
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

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, students, "
                    + "student_identifications, student_academic_history, student_documents, classes, sections CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolA + "', 'Admission School')");

            UUID adminRoleId = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID teacherRoleId = seedUser(st, TEACHER, "TEACHER");

            // The new Phase 3 endpoints (siblings/history/identifications/documents) are permission-based
            // (hasAuthority), not role-based -- self-seed exactly what these tests need, same fix as
            // RbacIntegrationTest/EnquiryIntegrationTest (the migration-seeded catalog only exists once,
            // and other test classes' @BeforeEach truncate roles/permissions too, shared test DB). Mirrors
            // the real V24 default grants: SCHOOL_ADMIN gets everything, TEACHER gets view + document
            // view/upload but not edit or document delete.
            String[] teacherPermissions = {"STUDENT_VIEW", "STUDENT_DOCUMENT_VIEW", "STUDENT_DOCUMENT_UPLOAD"};
            String[] names = {"STUDENT_VIEW", "STUDENT_EDIT", "STUDENT_DOCUMENT_VIEW", "STUDENT_DOCUMENT_UPLOAD", "STUDENT_DOCUMENT_DELETE"};
            for (String name : names) {
                UUID permId = UUID.randomUUID();
                st.execute("INSERT INTO permissions (id, name) VALUES ('" + permId + "', '" + name + "')");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('" + adminRoleId + "', '" + permId + "')");
                if (java.util.Arrays.asList(teacherPermissions).contains(name)) {
                    st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('" + teacherRoleId + "', '" + permId + "')");
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

    private UUID createStudent(Cookie admin, String firstName, String lastName, String admissionNumber) throws Exception {
        var result = mockMvc.perform(post("/api/v1/students").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"firstName\":\"" + firstName + "\",\"lastName\":\"" + lastName
                                + "\",\"admissionNumber\":\"" + admissionNumber + "\",\"dateOfBirth\":\"2015-01-01\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID createSection(Cookie admin, String className, String sectionName) throws Exception {
        var classResult = mockMvc.perform(post("/api/v1/classes").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"name\":\"" + className + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID classId = UUID.fromString(JSON.readTree(classResult.getResponse().getContentAsString()).get("id").asText());

        var sectionResult = mockMvc.perform(post("/api/v1/classes/" + classId + "/sections").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + sectionName + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JSON.readTree(sectionResult.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Full admission form --------------------------------------------

    @Test
    void createAcceptsTheFullAdmissionFormFieldSet() throws Exception {
        Cookie admin = login(ADMIN);

        mockMvc.perform(post("/api/v1/students").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolA + "\",\"firstName\":\"Priya\",\"middleName\":\"R\",\"lastName\":\"Sharma\","
                                + "\"admissionNumber\":\"ADM-FULL-1\",\"dateOfBirth\":\"2015-06-01\",\"gender\":\"FEMALE\","
                                + "\"religion\":\"Hindu\",\"category\":\"General\",\"enrollmentNumber\":\"ENR-1\","
                                + "\"previousSchoolName\":\"Old School\",\"previousSchoolClass\":\"Grade 4\","
                                + "\"transferCertificateNumber\":\"TC-99\",\"admissionSource\":\"Website\",\"rteStatus\":true,"
                                + "\"addressLine1\":\"12 Main St\",\"city\":\"Chennai\",\"state\":\"TN\",\"currentCountry\":\"India\","
                                + "\"pincode\":\"600028\",\"permanentSameAsCurrentAddress\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Priya R Sharma"))
                .andExpect(jsonPath("$.religion").value("Hindu"))
                .andExpect(jsonPath("$.category").value("General"))
                .andExpect(jsonPath("$.enrollmentNumber").value("ENR-1"))
                .andExpect(jsonPath("$.rteStatus").value(true))
                .andExpect(jsonPath("$.permanentCity").value("Chennai"))
                .andExpect(jsonPath("$.permanentPincode").value("600028"));
    }

    @Test
    void statusAcceptsAllFiveLifecycleValues() throws Exception {
        Cookie admin = login(ADMIN);
        UUID studentId = createStudent(admin, "Grad", "Uate", "ADM-STATUS-1");

        for (String status : new String[] {"GRADUATED", "LEFT_SCHOOL", "TRANSFERRED", "INACTIVE", "ACTIVE"}) {
            mockMvc.perform(patch("/api/v1/students/" + studentId + "/status").cookie(admin)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(status));
        }
    }

    // --- Search / filters -------------------------------------------

    @Test
    void searchFiltersByQueryAndGenderAndStatus() throws Exception {
        Cookie admin = login(ADMIN);
        UUID zoeId = createStudent(admin, "Zoe", "Zephyr", "ADM-SEARCH-1");
        createStudent(admin, "Someone", "Else", "ADM-SEARCH-2");
        mockMvc.perform(put("/api/v1/students/" + zoeId).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Zoe\",\"lastName\":\"Zephyr\",\"gender\":\"FEMALE\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/students").cookie(admin).param("q", "zoe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(zoeId.toString()));

        mockMvc.perform(get("/api/v1/students").cookie(admin).param("gender", "FEMALE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(zoeId.toString()));
    }

    // --- Siblings -----------------------------------------------------

    @Test
    void linkingASiblingMakesEachVisibleInTheOthersSiblingList() throws Exception {
        Cookie admin = login(ADMIN);
        UUID first = createStudent(admin, "First", "Kid", "ADM-SIB-1");
        UUID second = createStudent(admin, "Second", "Kid", "ADM-SIB-2");

        mockMvc.perform(post("/api/v1/students/" + first + "/siblings").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"siblingId\":\"" + second + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.familyId").isNotEmpty());

        mockMvc.perform(get("/api/v1/students/" + first + "/siblings").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(second.toString()));

        mockMvc.perform(get("/api/v1/students/" + second + "/siblings").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(first.toString()));
    }

    @Test
    void teacherCannotLinkSiblingsButCanViewThem() throws Exception {
        Cookie admin = login(ADMIN);
        UUID first = createStudent(admin, "First", "Kid", "ADM-SIB-3");
        UUID second = createStudent(admin, "Second", "Kid", "ADM-SIB-4");

        mockMvc.perform(post("/api/v1/students/" + first + "/siblings").cookie(login(TEACHER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"siblingId\":\"" + second + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/students/" + first + "/siblings").cookie(login(TEACHER)))
                .andExpect(status().isOk());
    }

    // --- Academic history -------------------------------------------

    @Test
    void assigningASectionAppendsAcademicHistoryWithoutOverwritingIt() throws Exception {
        Cookie admin = login(ADMIN);
        UUID sectionA = createSection(admin, "Grade 5", "A");
        UUID sectionB = createSection(admin, "Grade 6", "A");
        UUID studentId = createStudent(admin, "History", "Kid", "ADM-HIST-1");

        mockMvc.perform(patch("/api/v1/students/" + studentId + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionA + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/students/" + studentId + "/section").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sectionId\":\"" + sectionB + "\"}"))
                .andExpect(status().isOk());

        var result = mockMvc.perform(get("/api/v1/students/" + studentId + "/academic-history").cookie(admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode history = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("sectionId").asText()).isEqualTo(sectionB.toString());
        assertThat(history.get(1).get("sectionId").asText()).isEqualTo(sectionA.toString());
    }

    // --- Identifications ------------------------------------------------

    @Test
    void addingAndListingAndRemovingAnIdentification() throws Exception {
        Cookie admin = login(ADMIN);
        UUID studentId = createStudent(admin, "Ident", "Kid", "ADM-ID-1");

        var created = mockMvc.perform(post("/api/v1/students/" + studentId + "/identifications").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idType\":\"national_id\",\"idValue\":\"XYZ-123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idType").value("NATIONAL_ID"))
                .andReturn();
        UUID identificationId = UUID.fromString(JSON.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/students/" + studentId + "/identifications").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(delete("/api/v1/students/" + studentId + "/identifications/" + identificationId).cookie(admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/students/" + studentId + "/identifications").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- Documents ----------------------------------------------------

    @Test
    void uploadingListingDownloadingAndDeletingADocument() throws Exception {
        Cookie admin = login(ADMIN);
        UUID studentId = createStudent(admin, "Doc", "Kid", "ADM-DOC-1");

        MockMultipartFile file = new MockMultipartFile("file", "birth-cert.pdf", "application/pdf",
                "not a real pdf but good enough".getBytes());

        var result = mockMvc.perform(multipart("/api/v1/students/" + studentId + "/documents")
                        .file(file)
                        .part(new MockPart("documentType", "BIRTH_CERTIFICATE".getBytes()))
                        .cookie(admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentType").value("BIRTH_CERTIFICATE"))
                .andExpect(jsonPath("$.originalFilename").value("birth-cert.pdf"))
                .andReturn();
        UUID documentId = UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/students/" + studentId + "/documents").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/v1/students/" + studentId + "/documents/" + documentId + "/download").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("birth-cert.pdf")));

        mockMvc.perform(delete("/api/v1/students/" + studentId + "/documents/" + documentId).cookie(admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/students/" + studentId + "/documents").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void uploadingAnUnsupportedFileTypeReturns400() throws Exception {
        Cookie admin = login(ADMIN);
        UUID studentId = createStudent(admin, "Bad", "Upload", "ADM-DOC-2");
        MockMultipartFile file = new MockMultipartFile("file", "virus.exe", "application/x-msdownload", "x".getBytes());

        mockMvc.perform(multipart("/api/v1/students/" + studentId + "/documents")
                        .file(file).part(new MockPart("documentType", "OTHER".getBytes())).cookie(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCanUploadButNotDeleteDocuments() throws Exception {
        Cookie admin = login(ADMIN);
        UUID studentId = createStudent(admin, "Perm", "Check", "ADM-DOC-3");
        MockMultipartFile file = new MockMultipartFile("file", "note.pdf", "application/pdf", "x".getBytes());

        var result = mockMvc.perform(multipart("/api/v1/students/" + studentId + "/documents")
                        .file(file).part(new MockPart("documentType", "OTHER".getBytes())).cookie(login(TEACHER)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID documentId = UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(delete("/api/v1/students/" + studentId + "/documents/" + documentId).cookie(login(TEACHER)))
                .andExpect(status().isForbidden());
    }
}
