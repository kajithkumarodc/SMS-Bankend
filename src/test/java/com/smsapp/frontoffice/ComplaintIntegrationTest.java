package com.smsapp.frontoffice;

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
 * Complaints against a real PostgreSQL instance: the database-assigned Complain #, complaint types and the shared
 * source list, search/sort, the single attached document, and COMPLAINT_* permission gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ComplaintIntegrationTest {

    private static final String ADMIN = "cp-admin@school.example";
    private static final String RECEPTIONIST = "cp-receptionist@school.example";
    private static final String PRINCIPAL = "cp-principal@school.example";
    private static final String TEACHER = "cp-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID feesTypeId;
    private UUID transportTypeId;
    private UUID frontOfficeSourceId;

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
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, complaints, complaint_types, "
                    + "enquiry_sources CASCADE");
            feesTypeId = UUID.randomUUID();
            transportTypeId = UUID.randomUUID();
            frontOfficeSourceId = UUID.randomUUID();
            st.execute("INSERT INTO complaint_types (id, name) VALUES ('" + feesTypeId + "', 'Fees'), ('"
                    + transportTypeId + "', 'Transport')");
            st.execute("INSERT INTO enquiry_sources (id, name) VALUES ('" + frontOfficeSourceId + "', 'Front Office')");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID receptionistRole = seedUser(st, RECEPTIONIST, "RECEPTIONIST");
            UUID principalRole = seedUser(st, PRINCIPAL, "PRINCIPAL");
            seedUser(st, TEACHER, "TEACHER");
            // Same grants V36 makes (other test classes truncate roles/permissions).
            grant(st, adminRole, "COMPLAINT_VIEW", "COMPLAINT_CREATE", "COMPLAINT_EDIT", "COMPLAINT_DELETE",
                    "COMPLAINT_EXPORT", "COMPLAINT_PRINT");
            grant(st, receptionistRole, "COMPLAINT_VIEW", "COMPLAINT_CREATE", "COMPLAINT_EDIT");
            grant(st, principalRole, "COMPLAINT_VIEW", "COMPLAINT_EXPORT");
        }
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
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
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String complaintJson(String complainBy, Object... overrides) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("complaintTypeId", feesTypeId);
        body.put("sourceId", frontOfficeSourceId);
        body.put("complainBy", complainBy);
        body.put("phone", "7567575755");
        body.put("complaintDate", "2026-09-26");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(body);
    }

    private JsonNode createComplaint(Cookie caller, String complainBy, Object... overrides) throws Exception {
        var result = mockMvc.perform(post("/api/v1/complaints").cookie(caller)
                        .contentType(MediaType.APPLICATION_JSON).content(complaintJson(complainBy, overrides)))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void complaintsGetSequentialNumbersAndTheirTypeAndSourceNames() throws Exception {
        Cookie admin = login(ADMIN);
        JsonNode first = createComplaint(admin, "Nelson", "description", "Fee receipt not issued",
                "actionTaken", "Receipt re-issued", "assigned", "Accounts desk", "note", "Follow up Monday");
        JsonNode second = createComplaint(admin, "Rakesh Patel", "complaintTypeId", transportTypeId);

        assertThat(first.get("complaintNo").asLong()).isPositive();
        assertThat(second.get("complaintNo").asLong()).isEqualTo(first.get("complaintNo").asLong() + 1);
        assertThat(first.get("complaintTypeName").asText()).isEqualTo("Fees");
        assertThat(first.get("sourceName").asText()).isEqualTo("Front Office");
        assertThat(first.get("actionTaken").asText()).isEqualTo("Receipt re-issued");
        assertThat(first.get("assigned").asText()).isEqualTo("Accounts desk");
        assertThat(second.get("complaintTypeName").asText()).isEqualTo("Transport");
    }

    @Test
    void theComplaintNumberCannotBeChangedByAnEdit() throws Exception {
        Cookie admin = login(ADMIN);
        JsonNode created = createComplaint(admin, "Nelson");
        mockMvc.perform(put("/api/v1/complaints/" + created.get("id").asText()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(complaintJson("Nelson D'Souza", "complaintNo", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complainBy").value("Nelson D'Souza"))
                .andExpect(jsonPath("$.complaintNo").value(created.get("complaintNo").asLong()));
    }

    @Test
    void createValidatesTheForm() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(post("/api/v1/complaints").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(complaintJson(" "))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/complaints").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(complaintJson("Bad phone", "phone", "call me"))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/complaints").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(complaintJson("No date", "complaintDate", null))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/complaints").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(complaintJson("Unknown type", "complaintTypeId", UUID.randomUUID()))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/complaints").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(complaintJson("Unknown source", "sourceId", UUID.randomUUID()))).andExpect(status().isNotFound());
        // Type and source are optional.
        createComplaint(admin, "Walk-in parent", "complaintTypeId", null, "sourceId", null, "phone", null);
    }

    @Test
    void listIsNewestFirstAndSearchableByNameOrNumber() throws Exception {
        Cookie admin = login(ADMIN);
        createComplaint(admin, "Mohan Lal", "complaintTypeId", transportTypeId);
        JsonNode meena = createComplaint(admin, "Meena Choudhary");
        long meenaNo = meena.get("complaintNo").asLong();

        mockMvc.perform(get("/api/v1/complaints").cookie(admin))
                .andExpect(jsonPath("$.content[0].complainBy").value("Meena Choudhary"));
        mockMvc.perform(get("/api/v1/complaints").cookie(admin).param("q", "mohan"))
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/v1/complaints").cookie(admin).param("q", "#" + meenaNo))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].complainBy").value("Meena Choudhary"));
        mockMvc.perform(get("/api/v1/complaints").cookie(admin).param("sort", "complaintTypeName,desc"))
                .andExpect(jsonPath("$.content[0].complaintTypeName").value("Transport"));
        mockMvc.perform(get("/api/v1/complaints").cookie(admin).param("sort", "attachmentStoredFilename,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void complaintTypesAreListed() throws Exception {
        mockMvc.perform(get("/api/v1/complaint-types").cookie(login(RECEPTIONIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Fees"));
    }

    @Test
    void attachReplaceDownloadAndRemoveTheDocument() throws Exception {
        Cookie receptionist = login(RECEPTIONIST);
        String id = createComplaint(receptionist, "Shilpa Arora").get("id").asText();
        byte[] first = "%PDF-1.4 complaint letter".getBytes();
        byte[] second = "%PDF-1.4 revised letter".getBytes();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/complaints/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "letter.pdf", "application/pdf", first)).cookie(receptionist))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.fileName").value("letter.pdf"));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/complaints/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "revised.pdf", "application/pdf", second)).cookie(receptionist))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.fileName").value("revised.pdf"));
        // Replacing removed the first file: only the new one is on disk.
        assertThat(Path.of("target/test-uploads/complaints", id).toFile().list()).hasSize(1);
        mockMvc.perform(get("/api/v1/complaints/" + id + "/attachment").cookie(receptionist))
                .andExpect(status().isOk())
                .andExpect(content().bytes(second));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/complaints/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", first)).cookie(receptionist))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/complaints/" + id + "/attachment").cookie(receptionist))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment").doesNotExist());
        mockMvc.perform(get("/api/v1/complaints/" + id + "/attachment").cookie(receptionist))
                .andExpect(status().isNotFound());
        assertThat(Path.of("target/test-uploads/complaints", id).toFile().list()).isEmpty();
    }

    @Test
    void deletingAComplaintRemovesItsDocumentAndFolder() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createComplaint(admin, "Bella McCallum").get("id").asText();
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/complaints/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "scan.png", "image/png", new byte[] {1, 2})).cookie(admin))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/complaints/" + id).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/complaints/" + id).cookie(admin)).andExpect(status().isNotFound());
        assertThat(Path.of("target/test-uploads/complaints", id)).doesNotExist();
    }

    @Test
    void permissionsFollowV36Grants() throws Exception {
        String id = createComplaint(login(ADMIN), "Nelson").get("id").asText();

        Cookie receptionist = login(RECEPTIONIST);
        mockMvc.perform(put("/api/v1/complaints/" + id).cookie(receptionist).contentType(MediaType.APPLICATION_JSON)
                .content(complaintJson("Nelson (edited)"))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/complaints/" + id).cookie(receptionist)).andExpect(status().isForbidden());

        Cookie principal = login(PRINCIPAL);
        mockMvc.perform(get("/api/v1/complaints").cookie(principal)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/complaints").cookie(principal).contentType(MediaType.APPLICATION_JSON)
                .content(complaintJson("Nope"))).andExpect(status().isForbidden());

        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/complaints").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/complaint-types").cookie(teacher)).andExpect(status().isForbidden());
    }
}
