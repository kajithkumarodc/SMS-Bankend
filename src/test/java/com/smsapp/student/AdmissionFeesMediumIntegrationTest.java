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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mediums, fee structures per class + medium with editable dated fee lines, and CSV student import. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdmissionFeesMediumIntegrationTest {

    private static final String ADMIN = "medium-admin@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolId = UUID.randomUUID();
        try (Connection connection = superuser(); Statement st = connection.createStatement()) {
            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, students, "
                    + "student_identifications, student_academic_history, student_documents, classes, sections, "
                    + "fee_structures, fee_structure_items, fee_discounts, invoices, fee_adjustments, mediums CASCADE");
            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Medium School')");
            UUID userId = UUID.randomUUID();
            UUID roleId = UUID.randomUUID();
            st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + ADMIN
                    + "', '" + passwordEncoder.encode("secret") + "', 'Admin')");
            st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', 'SCHOOL_ADMIN')");
            st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        }
    }

    private Cookie login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
    }

    private JsonNode send(Cookie admin, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String body, int expected) throws Exception {
        var result = mockMvc.perform(request.cookie(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
        String text = result.getResponse().getContentAsString();
        return text.isEmpty() ? null : JSON.readTree(text);
    }

    @Test
    void mediumsCanBeManagedAndInUseOnesAreDeactivatedInsteadOfDeleted() throws Exception {
        Cookie admin = login();
        UUID english = UUID.fromString(send(admin, post("/api/v1/mediums"), "{\"name\":\"English Medium\"}", 201)
                .get("id").asText());
        send(admin, post("/api/v1/mediums"), "{\"name\":\"english medium\"}", 409);
        UUID tamil = UUID.fromString(send(admin, post("/api/v1/mediums"), "{\"name\":\"Tamil\"}", 201).get("id").asText());
        send(admin, put("/api/v1/mediums/" + tamil), "{\"name\":\"Tamil Medium\"}", 200);

        UUID classId = UUID.fromString(send(admin, post("/api/v1/classes"),
                "{\"schoolId\":\"" + schoolId + "\",\"name\":\"LKG\"}", 201).get("id").asText());
        send(admin, post("/api/v1/fee-structures"), feeBody(classId, english, "LKG General", "1500.00"), 201);

        // English is used by a fee structure -> deactivated; Tamil is unused -> deleted.
        send(admin, delete("/api/v1/mediums/" + english), "", 200);
        send(admin, delete("/api/v1/mediums/" + tamil), "", 204);
        mockMvc.perform(get("/api/v1/mediums").cookie(admin))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].active").value(false));
    }

    private String feeBody(UUID classId, UUID mediumId, String name, String secondAmount) {
        return "{\"schoolId\":\"" + schoolId + "\",\"classId\":\"" + classId + "\",\"academicYear\":\"2026-2027\","
                + "\"name\":\"" + name + "\",\"dueDate\":\"2026-06-10\"," + (mediumId == null ? "" : "\"mediumId\":\"" + mediumId + "\",")
                + "\"items\":[{\"category\":\"ADMISSION\",\"label\":\"Admission Fees\",\"amount\":2500.00},"
                + "{\"category\":\"OTHER\",\"label\":\"June Month Fees\",\"amount\":" + secondAmount
                + ",\"dueDate\":\"2026-06-15\"}]}";
    }

    @Test
    void feeStructuresAreFilteredByMediumAndCanBeEditedAndDeleted() throws Exception {
        Cookie admin = login();
        UUID english = UUID.fromString(send(admin, post("/api/v1/mediums"), "{\"name\":\"English Medium\"}", 201).get("id").asText());
        UUID tamil = UUID.fromString(send(admin, post("/api/v1/mediums"), "{\"name\":\"Tamil Medium\"}", 201).get("id").asText());
        UUID classId = UUID.fromString(send(admin, post("/api/v1/classes"),
                "{\"schoolId\":\"" + schoolId + "\",\"name\":\"UKG\"}", 201).get("id").asText());

        JsonNode englishFees = send(admin, post("/api/v1/fee-structures"), feeBody(classId, english, "UKG English", "350.00"), 201);
        send(admin, post("/api/v1/fee-structures"), feeBody(classId, tamil, "UKG Tamil", "300.00"), 201);
        send(admin, post("/api/v1/fee-structures"), feeBody(classId, null, "UKG Common", "100.00"), 201);
        assertThat(englishFees.get("amount").decimalValue()).isEqualByComparingTo("2850.00");
        assertThat(englishFees.get("items").get(1).get("dueDate").asText()).isEqualTo("2026-06-15");
        assertThat(englishFees.get("mediumId").asText()).isEqualTo(english.toString());

        // English medium sees its own fees plus the ones for every medium, never the Tamil ones.
        mockMvc.perform(get("/api/v1/fee-structures").param("classId", classId.toString())
                        .param("mediumId", english.toString()).cookie(admin))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.name == 'UKG Tamil')]").isEmpty());

        // Edit: the fee lines are replaced and the total recomputed.
        UUID id = UUID.fromString(englishFees.get("id").asText());
        JsonNode edited = send(admin, put("/api/v1/fee-structures/" + id),
                feeBody(classId, english, "UKG English Medium", "400.00"), 200).get("structure");
        assertThat(edited.get("name").asText()).isEqualTo("UKG English Medium");
        assertThat(edited.get("amount").decimalValue()).isEqualByComparingTo("2900.00");
        assertThat(edited.get("items")).hasSize(2);

        send(admin, delete("/api/v1/fee-structures/" + id), "", 204);
        send(admin, delete("/api/v1/fee-structures/" + id), "", 404);
    }

    @Test
    void admissionAdjustmentsAndRetroactiveFeeChangesAreRecorded() throws Exception {
        Cookie admin = login();
        UUID classId = UUID.fromString(send(admin, post("/api/v1/classes"),
                "{\"schoolId\":\"" + schoolId + "\",\"name\":\"Class 2\"}", 201).get("id").asText());
        UUID sectionId = UUID.fromString(send(admin, post("/api/v1/classes/" + classId + "/sections"),
                "{\"name\":\"A\"}", 201).get("id").asText());
        UUID structureId = UUID.fromString(send(admin, post("/api/v1/fee-structures"),
                feeBody(classId, null, "Class 2 Fees", "500.00"), 201).get("id").asText()); // total 3000
        UUID[] students = new UUID[2];
        for (int i = 0; i < 2; i++) {
            students[i] = UUID.fromString(send(admin, post("/api/v1/students"), "{\"schoolId\":\"" + schoolId
                    + "\",\"sectionId\":\"" + sectionId + "\",\"firstName\":\"Kid" + i + "\",\"admissionNumber\":\"ADJ-"
                    + i + "\",\"dateOfBirth\":\"2018-01-01\"}", 201).get("id").asText());
        }

        // Admission: one student gets the template amount, the other an adjusted amount.
        send(admin, post("/api/v1/invoices"), "{\"studentId\":\"" + students[0] + "\",\"feeStructureId\":\"" + structureId + "\"}", 201);
        JsonNode adjusted = send(admin, post("/api/v1/invoices"), "{\"studentId\":\"" + students[1] + "\",\"feeStructureId\":\""
                + structureId + "\",\"amount\":2700.00,\"adjustmentReason\":\"Staff child\"}", 201);
        assertThat(adjusted.get("amount").decimalValue()).isEqualByComparingTo("2700.00");

        // Edit without applyToExisting: bills unchanged. With it: both move by +500 (keeping the adjustment).
        JsonNode futureOnly = send(admin, put("/api/v1/fee-structures/" + structureId),
                feeBody(classId, null, "Class 2 Fees", "1000.00"), 200);
        assertThat(futureOnly.get("adjustedInvoices").asInt()).isZero();
        assertThat(futureOnly.get("structure").get("billedCount").asInt()).isEqualTo(2);
        JsonNode retro = send(admin, put("/api/v1/fee-structures/" + structureId + "?applyToExisting=true"),
                feeBody(classId, null, "Class 2 Fees", "1500.00"), 200);
        assertThat(retro.get("adjustedInvoices").asInt()).isEqualTo(2);

        JsonNode bills = JSON.readTree(mockMvc.perform(get("/api/v1/invoices").param("studentId", students[1].toString())
                .cookie(admin)).andReturn().getResponse().getContentAsString());
        assertThat(bills.get(0).get("amount").decimalValue()).isEqualByComparingTo("3200.00");
        try (Connection connection = superuser(); Statement st = connection.createStatement();
             var rs = st.executeQuery("SELECT kind, count(*) FROM fee_adjustments GROUP BY kind ORDER BY kind")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("ADMISSION_ADJUSTMENT");
            assertThat(rs.getInt(2)).isEqualTo(1);
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("STRUCTURE_CHANGE");
            assertThat(rs.getInt(2)).isEqualTo(2);
        }
    }

    @Test
    void feeTypesCanBeRenamedAndDeactivated() throws Exception {
        Cookie admin = login();
        try (Connection connection = superuser(); Statement st = connection.createStatement()) {
            st.execute("DELETE FROM fee_types WHERE name LIKE 'Zz %'");
            // Fee type endpoints are permission-based.
            for (String name : new String[]{"FEE_VIEW", "FEE_EDIT"}) {
                UUID permId = UUID.randomUUID();
                st.execute("INSERT INTO permissions (id, name) VALUES ('" + permId + "', '" + name + "')");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT id, '" + permId
                        + "' FROM roles WHERE name = 'SCHOOL_ADMIN'");
            }
        }
        admin = login();
        UUID typeId = UUID.fromString(send(admin, post("/api/v1/fee-types"), "{\"name\":\"Zz Lab\"}", 201).get("id").asText());
        send(admin, put("/api/v1/fee-types/" + typeId), "{\"name\":\"Zz Lab Fee\"}", 200);
        send(admin, put("/api/v1/fee-types/" + typeId), "{\"name\":\"Zz Lab Fee\",\"active\":false}", 200);
        mockMvc.perform(get("/api/v1/fee-types").cookie(admin))
                .andExpect(jsonPath("$[?(@.name == 'Zz Lab Fee')]").isEmpty());
        mockMvc.perform(get("/api/v1/fee-types").param("includeInactive", "true").cookie(admin))
                .andExpect(jsonPath("$[?(@.name == 'Zz Lab Fee')].active").value(org.hamcrest.Matchers.contains(false)));
        try (Connection connection = superuser(); Statement st = connection.createStatement()) {
            st.execute("DELETE FROM fee_types WHERE name LIKE 'Zz %'");
        }
    }

    @Test
    void studentsAreImportedFromTheSampleCsvFormat() throws Exception {
        Cookie admin = login();
        UUID classId = UUID.fromString(send(admin, post("/api/v1/classes"),
                "{\"schoolId\":\"" + schoolId + "\",\"name\":\"Class 1\"}", 201).get("id").asText());
        UUID sectionId = UUID.fromString(send(admin, post("/api/v1/classes/" + classId + "/sections"),
                "{\"name\":\"A\"}", 201).get("id").asText());
        UUID medium = UUID.fromString(send(admin, post("/api/v1/mediums"), "{\"name\":\"English Medium\"}", 201).get("id").asText());

        String csv = String.join(",", StudentImportService.COLUMNS) + "\n"
                // The Smart School sample row: blood group "A" and 8-digit parent phones are left out as warnings.
                + "19001,201,Edward,,Thomas,Male,2014-11-03,,,,8233366613,thomas@gmail.com,2021-03-18,A,,4'2,34 kg,,"
                + "Olivier Thomas,98654646,Lawyer,Caroline Thomas,6598656,Teacher,Father,Olivier Thomas,Father,,"
                + "98654646,Lawyer,West Brooklyn,West Brooklyn,West Brooklyn,68654, UBS Bank,UBS5644,46464746,446464,,,\n"
                + "19002,,Sara,,,Female,2015-01-20,,,,,,,,,,,,,,,,,,,,,,,,,\"12, Main Road, Karur\",,,,,,,Yes,,\n"
                + "19001,,Dup,,,Male,2015-01-20,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,\n"
                + "19003,,NoDob,,,Male,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,\n";
        MockMultipartFile file = new MockMultipartFile("file", "students.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        var result = mockMvc.perform(multipart("/api/v1/students/import").file(file)
                        .param("sectionId", sectionId.toString()).param("mediumId", medium.toString()).cookie(admin))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("imported").asInt()).isEqualTo(2);
        assertThat(body.get("skipped").asInt()).isEqualTo(2);
        JsonNode rows = body.get("rows");
        assertThat(rows.get(0).get("status").asText()).isEqualTo("IMPORTED");
        assertThat(rows.get(0).get("messages").toString()).contains("Blood group").contains("Father Phone");
        assertThat(rows.get(2).get("messages").get(0).asText()).contains("already exists");
        assertThat(rows.get(3).get("messages").get(0).asText()).contains("Date of birth is required");

        JsonNode edward = JSON.readTree(mockMvc.perform(get("/api/v1/students").param("q", "19001").cookie(admin))
                .andReturn().getResponse().getContentAsString()).get("content").get(0);
        assertThat(edward.get("fullName").asText()).isEqualTo("Edward Thomas");
        assertThat(edward.get("sectionId").asText()).isEqualTo(sectionId.toString());
        assertThat(edward.get("extra").get("mediumId").asText()).isEqualTo(medium.toString());
        assertThat(edward.get("extra").get("mobileNumber").asText()).isEqualTo("8233366613");
        assertThat(edward.get("extra").get("height").asText()).isEqualTo("4'2");
        assertThat(edward.get("guardianRelationship").asText()).isEqualTo("FATHER");
        assertThat(edward.get("addressLine1").asText()).isEqualTo("West Brooklyn");
        // National / local ID numbers are searchable.
        mockMvc.perform(get("/api/v1/students").param("q", "446464").cookie(admin))
                .andExpect(jsonPath("$.content.length()").value(1));

        JsonNode sara = JSON.readTree(mockMvc.perform(get("/api/v1/students").param("q", "19002").cookie(admin))
                .andReturn().getResponse().getContentAsString()).get("content").get(0);
        assertThat(sara.get("fullName").asText()).isEqualTo("Sara");
        assertThat(sara.get("addressLine1").asText()).isEqualTo("12, Main Road, Karur");
        assertThat(sara.get("rteStatus").asBoolean()).isTrue();

        MockMultipartFile notCsv = new MockMultipartFile("file", "students.xlsx", "application/octet-stream", new byte[]{1});
        mockMvc.perform(multipart("/api/v1/students/import").file(notCsv).param("sectionId", sectionId.toString())
                .cookie(admin)).andExpect(status().isBadRequest());
    }
}
