package com.smsapp.expense;

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
 * Expenses against a real PostgreSQL instance: validation, expense heads, search/sort, the single attached
 * document, and EXPENSE_* permission gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpenseIntegrationTest {

    private static final String ADMIN = "ex-admin@school.example";
    private static final String PRINCIPAL = "ex-principal@school.example";
    private static final String TEACHER = "ex-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID stationeryId;
    private UUID telephoneId;
    private UUID retiredId;

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
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, expenses, expense_heads CASCADE");
            stationeryId = UUID.randomUUID();
            telephoneId = UUID.randomUUID();
            retiredId = UUID.randomUUID();
            st.execute("INSERT INTO expense_heads (id, name) VALUES ('" + stationeryId + "', 'Stationery Purchase'), ('"
                    + telephoneId + "', 'Telephone Bill')");
            st.execute("INSERT INTO expense_heads (id, name, active) VALUES ('" + retiredId + "', 'Retired Head', false)");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID principalRole = seedUser(st, PRINCIPAL, "PRINCIPAL");
            seedUser(st, TEACHER, "TEACHER");
            // Same grants V43 makes (other test classes truncate roles/permissions).
            grant(st, adminRole, "EXPENSE_VIEW", "EXPENSE_CREATE", "EXPENSE_EDIT", "EXPENSE_DELETE",
                    "EXPENSE_EXPORT", "EXPENSE_PRINT");
            grant(st, principalRole, "EXPENSE_VIEW", "EXPENSE_EXPORT");
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

    private String expenseJson(String name, Object... overrides) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("expenseHeadId", stationeryId);
        body.put("name", name);
        body.put("invoiceNumber", "56564");
        body.put("expenseDate", "2026-10-03");
        body.put("amount", "350.00");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(body);
    }

    private JsonNode createExpense(Cookie caller, String name, Object... overrides) throws Exception {
        var result = mockMvc.perform(post("/api/v1/expenses").cookie(caller)
                        .contentType(MediaType.APPLICATION_JSON).content(expenseJson(name, overrides)))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createReturnsTheExpenseWithItsHeadName() throws Exception {
        JsonNode created = createExpense(login(ADMIN), "Online Course Classes", "description", "Term books");
        assertThat(created.get("expenseHeadName").asText()).isEqualTo("Stationery Purchase");
        assertThat(created.get("amount").decimalValue()).isEqualByComparingTo("350.00");
        assertThat(created.get("invoiceNumber").asText()).isEqualTo("56564");
        assertThat(created.get("description").asText()).isEqualTo("Term books");
        assertThat(created.get("attachment").isNull()).isTrue();
    }

    @Test
    void createValidatesTheForm() throws Exception {
        Cookie admin = login(ADMIN);
        postExpense(admin, expenseJson(" ")).andExpect(status().isBadRequest());
        postExpense(admin, expenseJson("No head", "expenseHeadId", null)).andExpect(status().isBadRequest());
        postExpense(admin, expenseJson("No date", "expenseDate", null)).andExpect(status().isBadRequest());
        postExpense(admin, expenseJson("No amount", "amount", null)).andExpect(status().isBadRequest());
        postExpense(admin, expenseJson("Zero", "amount", "0")).andExpect(status().isBadRequest());
        postExpense(admin, expenseJson("Negative", "amount", "-5")).andExpect(status().isBadRequest());
        postExpense(admin, expenseJson("Fractions", "amount", "10.123")).andExpect(status().isBadRequest());
        postExpense(admin, expenseJson("Unknown head", "expenseHeadId", UUID.randomUUID())).andExpect(status().isNotFound());
        postExpense(admin, expenseJson("Retired head", "expenseHeadId", retiredId)).andExpect(status().isBadRequest());
        // Invoice number and description are optional.
        createExpense(admin, "Bare minimum", "invoiceNumber", null);
    }

    private org.springframework.test.web.servlet.ResultActions postExpense(Cookie caller, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/expenses").cookie(caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void updateChangesTheFieldsAndKeepsTheDocumentOut() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createExpense(admin, "Airtel Broad Band").get("id").asText();
        mockMvc.perform(put("/api/v1/expenses/" + id).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(expenseJson("Airtel Broadband", "expenseHeadId", telephoneId, "amount", "300.50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Airtel Broadband"))
                .andExpect(jsonPath("$.expenseHeadName").value("Telephone Bill"))
                .andExpect(jsonPath("$.amount").value(300.5));
        mockMvc.perform(put("/api/v1/expenses/" + UUID.randomUUID()).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(expenseJson("Ghost"))).andExpect(status().isNotFound());
    }

    @Test
    void listIsNewestDateFirstSearchableAndSortable() throws Exception {
        Cookie admin = login(ADMIN);
        createExpense(admin, "Airtel Broad Band", "expenseHeadId", telephoneId, "expenseDate", "2026-10-17", "amount", "300");
        createExpense(admin, "CBSE BOOKS", "expenseDate", "2026-10-07", "amount", "400", "invoiceNumber", "7758");
        createExpense(admin, "Online Course Classes", "expenseDate", "2026-10-26", "amount", "350");

        mockMvc.perform(get("/api/v1/expenses").cookie(admin))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].name").value("Online Course Classes"))
                .andExpect(jsonPath("$.page.totalElements").value(3));
        mockMvc.perform(get("/api/v1/expenses").cookie(admin).param("q", "airtel"))
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(get("/api/v1/expenses").cookie(admin).param("q", "7758"))
                .andExpect(jsonPath("$.content[0].name").value("CBSE BOOKS"));
        mockMvc.perform(get("/api/v1/expenses").cookie(admin).param("q", "telephone"))
                .andExpect(jsonPath("$.content[0].name").value("Airtel Broad Band"));
        mockMvc.perform(get("/api/v1/expenses").cookie(admin).param("sort", "amount,desc"))
                .andExpect(jsonPath("$.content[0].name").value("CBSE BOOKS"));
        mockMvc.perform(get("/api/v1/expenses").cookie(admin).param("sort", "expenseHeadName,desc"))
                .andExpect(jsonPath("$.content[0].expenseHeadName").value("Telephone Bill"));
        mockMvc.perform(get("/api/v1/expenses").cookie(admin).param("sort", "attachmentStoredFilename,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dateRangeAndTotalCoverEveryMatchAcrossPages() throws Exception {
        Cookie admin = login(ADMIN);
        createExpense(admin, "Online Course Classes", "expenseDate", "2026-09-30", "amount", "200.00", "invoiceNumber", "5464");
        createExpense(admin, "Flower Decor", "expenseDate", "2026-10-01", "amount", "1000.50", "invoiceNumber", "56467");
        createExpense(admin, "Old Bill", "expenseDate", "2026-08-01", "amount", "75");

        mockMvc.perform(get("/api/v1/expenses").cookie(admin).param("from", "2026-09-28").param("to", "2026-10-03"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Flower Decor"));
        mockMvc.perform(get("/api/v1/expenses/total").cookie(admin).param("from", "2026-09-28").param("to", "2026-10-03"))
                .andExpect(jsonPath("$.total").value(1200.5));
        // Bounds are inclusive.
        mockMvc.perform(get("/api/v1/expenses/total").cookie(admin).param("from", "2026-09-30").param("to", "2026-09-30"))
                .andExpect(jsonPath("$.total").value(200));
        // Text search and the table filter both apply; the total follows.
        mockMvc.perform(get("/api/v1/expenses/total").cookie(admin).param("q", "course").param("filter", "5464"))
                .andExpect(jsonPath("$.total").value(200));
        mockMvc.perform(get("/api/v1/expenses/total").cookie(admin).param("q", "course").param("filter", "flower"))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/v1/expenses/total").cookie(admin))
                .andExpect(jsonPath("$.total").value(1275.5));
        mockMvc.perform(get("/api/v1/expenses").cookie(admin).param("from", "2026-10-03").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/expenses/total").cookie(login(TEACHER))).andExpect(status().isForbidden());
    }

    @Test
    void expenseHeadsListOnlyActiveOnesAndCanBeAdded() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(get("/api/v1/expense-heads").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Stationery Purchase"));
        mockMvc.perform(post("/api/v1/expense-heads").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sports Equipment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Sports Equipment"));
        mockMvc.perform(post("/api/v1/expense-heads").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sports Equipment\"}")).andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/expense-heads").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \"}")).andExpect(status().isBadRequest());
    }

    @Test
    void expenseHeadsCanBeRenamedAndDeletedUnlessInUse() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(put("/api/v1/expense-heads/" + telephoneId).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phone and Internet\",\"description\":\"Monthly bills\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Phone and Internet"))
                .andExpect(jsonPath("$.description").value("Monthly bills"));
        // Keeping its own name is fine; taking another head's name is not.
        mockMvc.perform(put("/api/v1/expense-heads/" + telephoneId).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Phone and Internet\"}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/expense-heads/" + telephoneId).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Stationery Purchase\"}")).andExpect(status().isConflict());
        mockMvc.perform(put("/api/v1/expense-heads/" + UUID.randomUUID()).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ghost\"}")).andExpect(status().isNotFound());

        createExpense(admin, "Paper reams");
        mockMvc.perform(delete("/api/v1/expense-heads/" + stationeryId).cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/expense-heads/" + telephoneId).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/expense-heads/" + telephoneId).cookie(admin)).andExpect(status().isNotFound());

        Cookie principal = login(PRINCIPAL);
        mockMvc.perform(put("/api/v1/expense-heads/" + stationeryId).cookie(principal).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/expense-heads/" + stationeryId).cookie(principal)).andExpect(status().isForbidden());
    }

    @Test
    void attachReplaceDownloadAndRemoveTheDocument() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createExpense(admin, "CBSE BOOKS").get("id").asText();
        byte[] first = "%PDF-1.4 invoice".getBytes();
        byte[] second = "%PDF-1.4 revised invoice".getBytes();

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/expenses/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "invoice.pdf", "application/pdf", first)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.fileName").value("invoice.pdf"));
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/expenses/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "revised.pdf", "application/pdf", second)).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment.fileName").value("revised.pdf"));
        // Replacing removed the first file: only the new one is on disk.
        assertThat(Path.of("target/test-uploads/expenses", id).toFile().list()).hasSize(1);
        mockMvc.perform(get("/api/v1/expenses/" + id + "/attachment").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().bytes(second));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/expenses/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", first)).cookie(admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/expenses/" + id + "/attachment").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachment").doesNotExist());
        mockMvc.perform(get("/api/v1/expenses/" + id + "/attachment").cookie(admin))
                .andExpect(status().isNotFound());
        assertThat(Path.of("target/test-uploads/expenses", id).toFile().list()).isEmpty();
    }

    @Test
    void deletingAnExpenseRemovesItsDocumentAndFolder() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createExpense(admin, "Scan me").get("id").asText();
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/expenses/" + id + "/attachment")
                        .file(new MockMultipartFile("file", "scan.png", "image/png", new byte[] {1, 2})).cookie(admin))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/expenses/" + id).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/expenses/" + id).cookie(admin)).andExpect(status().isNotFound());
        assertThat(Path.of("target/test-uploads/expenses", id)).doesNotExist();
    }

    @Test
    void permissionsFollowV43Grants() throws Exception {
        String id = createExpense(login(ADMIN), "Seeded").get("id").asText();

        Cookie principal = login(PRINCIPAL);
        mockMvc.perform(get("/api/v1/expenses").cookie(principal)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/expense-heads").cookie(principal)).andExpect(status().isOk());
        postExpense(principal, expenseJson("Nope")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/expenses/" + id).cookie(principal)).andExpect(status().isForbidden());

        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/expenses").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/expense-heads").cookie(teacher)).andExpect(status().isForbidden());
    }
}
