package com.smsapp.inventory;

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
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Inventory against a real PostgreSQL instance: categories, items, issuing and returning, stock, and permissions. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryIntegrationTest {

    private static final String ADMIN = "iv-admin@school.example";
    private static final String TEACHER = "iv-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherRoleId;
    private Cookie admin;
    private String joe;
    private String shivam;
    private String sportsId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, staff_profiles, departments, designations, "
                    + "inventory_stock_entries, inventory_issues, inventory_items, inventory_categories, inventory_stores, inventory_suppliers CASCADE");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            seedUser(st, TEACHER, "TEACHER");
            teacherRoleId = UUID.randomUUID();
            st.execute("INSERT INTO roles (id, name) VALUES ('" + teacherRoleId + "', 'STAFF_TEACHER')");
            for (String name : new String[] {"INVENTORY_VIEW", "INVENTORY_ISSUE", "INVENTORY_MANAGE", "STAFF_VIEW", "STAFF_CREATE", "STAFF_EDIT"}) {
                st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + adminRole + "', id FROM permissions "
                        + "WHERE name = '" + name + "'");
            }
        }
        admin = login(ADMIN);
        joe = addStaff("9000", "Joe");
        shivam = addStaff("9002", "Shivam");
        sportsId = JSON.readTree(postJson("/api/v1/inventory/categories", Map.of("name", "Sports")).andReturn().getResponse().getContentAsString())
                .get("id").asText();
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

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private String addStaff(String staffId, String name) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("staffId", staffId);
        body.put("roleId", teacherRoleId);
        body.put("firstName", name);
        body.put("email", staffId + "@school.example");
        body.put("gender", "Male");
        body.put("dateOfBirth", "1990-04-12");
        body.put("panNumber", "ABCDE1234F");
        String response = mockMvc.perform(post("/api/v1/staff-members").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("staff").get("id").asText();
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).cookie(admin)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private String addItem(String name, int stock) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("categoryId", sportsId);
        body.put("unit", "Piece");
        String id = JSON.readTree(postJson("/api/v1/inventory/items", body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).get("id").asText();
        if (stock > 0) {
            postJson("/api/v1/inventory/stock", stockBody(id, stock, null, null)).andExpect(status().isCreated());
        }
        return id;
    }

    private Map<String, Object> issueBody(String itemId, int quantity) {
        var body = new LinkedHashMap<String, Object>();
        body.put("issueToStaffId", shivam);
        body.put("issuedByStaffId", joe);
        body.put("issueDate", "2026-10-03");
        body.put("returnDate", "2026-10-05");
        body.put("note", " For the match ");
        body.put("itemId", itemId);
        body.put("quantity", quantity);
        return body;
    }

    private int stockOf(String itemId) throws Exception {
        String json = mockMvc.perform(get("/api/v1/inventory/items").cookie(admin)).andReturn().getResponse().getContentAsString();
        for (var node : JSON.readTree(json)) {
            if (itemId.equals(node.get("id").asText())) {
                return node.get("stock").asInt();
            }
        }
        throw new AssertionError("item not listed");
    }

    @Test
    void categoriesAndItemsFollowTheNameRules() throws Exception {
        postJson("/api/v1/inventory/categories", Map.of("name", "sports")).andExpect(status().isConflict());
        postJson("/api/v1/inventory/categories", Map.of("name", " ")).andExpect(status().isBadRequest());
        String lab = idOf(postJson("/api/v1/inventory/categories", Map.of("name", "Chemistry Lab", "description", " Apparatus ")));
        mockMvc.perform(get("/api/v1/inventory/categories").cookie(admin)).andExpect(jsonPath("$[0].name").value("Chemistry Lab"))
                .andExpect(jsonPath("$[0].description").value("Apparatus"));
        mockMvc.perform(put("/api/v1/inventory/categories/" + lab).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Chemistry Lab\",\"description\":\"\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").doesNotExist());
        String bat = addItem("Cricket Bat", 0);
        var dup = new LinkedHashMap<String, Object>();
        dup.put("name", "cricket bat");
        dup.put("categoryId", sportsId);
        dup.put("unit", "Piece");
        postJson("/api/v1/inventory/items", dup).andExpect(status().isConflict());
        dup.put("unit", " ");
        postJson("/api/v1/inventory/items", dup).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/inventory/items").cookie(admin).param("categoryId", sportsId))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].categoryName").value("Sports"))
                .andExpect(jsonPath("$[0].unit").value("Piece")).andExpect(jsonPath("$[0].stock").value(0));
        mockMvc.perform(delete("/api/v1/inventory/categories/" + sportsId).cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/inventory/items/" + bat).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/inventory/categories/" + sportsId).cookie(admin)).andExpect(status().isNoContent());
    }

    @Test
    void issuingTakesStockAndReturningGivesItBack() throws Exception {
        String bat = addItem("Cricket Bat", 12);
        postJson("/api/v1/inventory/issues", issueBody(bat, 5)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.issueToName").value("Shivam"))
                .andExpect(jsonPath("$.issuedByCode").value("9000"))
                .andExpect(jsonPath("$.note").value("For the match"))
                .andExpect(jsonPath("$.categoryName").value("Sports"));
        org.junit.jupiter.api.Assertions.assertEquals(7, stockOf(bat));

        postJson("/api/v1/inventory/issues", issueBody(bat, 8)).andExpect(status().isConflict());
        org.junit.jupiter.api.Assertions.assertEquals(7, stockOf(bat));

        String id = JSON.readTree(mockMvc.perform(get("/api/v1/inventory/issues").cookie(admin)).andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asText();
        postJson("/api/v1/inventory/issues/" + id + "/return", Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETURNED"));
        org.junit.jupiter.api.Assertions.assertEquals(12, stockOf(bat));
        postJson("/api/v1/inventory/issues/" + id + "/return", Map.of()).andExpect(status().isConflict());
        // An item that has been issued can't be deleted.
        mockMvc.perform(delete("/api/v1/inventory/items/" + bat).cookie(admin)).andExpect(status().isConflict());
    }

    @Test
    void deletingAnIssueThatIsStillOutRestoresTheStock() throws Exception {
        String bat = addItem("Cricket Bat", 4);
        postJson("/api/v1/inventory/issues", issueBody(bat, 3)).andExpect(status().isCreated());
        String id = JSON.readTree(mockMvc.perform(get("/api/v1/inventory/issues").cookie(admin)).andReturn().getResponse()
                .getContentAsString()).get(0).get("id").asText();
        mockMvc.perform(delete("/api/v1/inventory/issues/" + id).cookie(admin)).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(4, stockOf(bat));
        mockMvc.perform(delete("/api/v1/inventory/issues/" + id).cookie(admin)).andExpect(status().isNotFound());
    }

    @Test
    void issueIsValidated() throws Exception {
        String bat = addItem("Cricket Bat", 4);
        var early = issueBody(bat, 1);
        early.put("returnDate", "2026-10-01");
        postJson("/api/v1/inventory/issues", early).andExpect(status().isBadRequest());
        postJson("/api/v1/inventory/issues", issueBody(bat, 0)).andExpect(status().isBadRequest());
        var unknownItem = issueBody(UUID.randomUUID().toString(), 1);
        postJson("/api/v1/inventory/issues", unknownItem).andExpect(status().isNotFound());
        var unknownStaff = issueBody(bat, 1);
        unknownStaff.put("issueToStaffId", UUID.randomUUID().toString());
        postJson("/api/v1/inventory/issues", unknownStaff).andExpect(status().isNotFound());
        // A disabled staff member can't be issued to.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/staff-members/" + shivam + "/status")
                .cookie(admin).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
        postJson("/api/v1/inventory/issues", issueBody(bat, 1)).andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals(4, stockOf(bat));
    }

    @Test
    void inventoryNeedsItsPermissions() throws Exception {
        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/inventory/issues").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inventory/categories").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/inventory/categories/" + sportsId).cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\"}")).andExpect(status().isForbidden());
    }

    // --- Stores, suppliers and stock entries ---------------------------------------------------------

    private String idOf(ResultActions result) throws Exception {
        return JSON.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private Map<String, Object> stockBody(String itemId, int quantity, String supplierId, String storeId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("itemId", itemId);
        body.put("supplierId", supplierId);
        body.put("storeId", storeId);
        body.put("quantity", quantity);
        body.put("purchasePrice", "250.00");
        body.put("date", "2026-10-01");
        body.put("description", " Bought for the term ");
        return body;
    }

    @Test
    void storesAndSuppliersFollowTheNameRules() throws Exception {
        String store = idOf(postJson("/api/v1/inventory/stores", Map.of("name", "Sports Store", "code", "sp55")));
        postJson("/api/v1/inventory/stores", Map.of("name", "sports store")).andExpect(status().isConflict());
        postJson("/api/v1/inventory/stores", Map.of("name", " ")).andExpect(status().isBadRequest());
        String supplier = idOf(postJson("/api/v1/inventory/suppliers", Map.of("name", "Camlin Stationers", "phone", "999")));
        postJson("/api/v1/inventory/suppliers", Map.of("name", "CAMLIN STATIONERS")).andExpect(status().isConflict());
        postJson("/api/v1/inventory/suppliers", Map.of("name", "Bad Mail", "email", "nope")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/inventory/stores").cookie(admin)).andExpect(jsonPath("$[0].code").value("sp55"));

        // Used by a stock entry -> can't be deleted.
        String bat = addItem("Cricket Bat", 0);
        postJson("/api/v1/inventory/stock", stockBody(bat, 3, supplier, store)).andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/inventory/stores/" + store).cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/inventory/suppliers/" + supplier).cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/inventory/items/" + bat).cookie(admin)).andExpect(status().isConflict());
    }

    @Test
    void stockEntriesMoveTheItemsStock() throws Exception {
        String store = idOf(postJson("/api/v1/inventory/stores", Map.of("name", "Sports Store", "code", "sp55")));
        String bat = addItem("Cricket Bat", 10);
        String ball = addItem("Ball", 0);

        String entry = idOf(postJson("/api/v1/inventory/stock", stockBody(bat, 5, null, store)));
        org.junit.jupiter.api.Assertions.assertEquals(15, stockOf(bat));
        mockMvc.perform(get("/api/v1/inventory/stock").cookie(admin))
                .andExpect(jsonPath("$[0].itemName").value("Cricket Bat"))
                .andExpect(jsonPath("$[0].categoryName").value("Sports"))
                .andExpect(jsonPath("$[0].storeName").value("Sports Store (sp55)"))
                .andExpect(jsonPath("$[0].description").value("Bought for the term"))
                .andExpect(jsonPath("$[0].purchasePrice").value(250.00));

        // Negative quantities take units out, but never below zero.
        postJson("/api/v1/inventory/stock", stockBody(bat, -4, null, null)).andExpect(status().isCreated());
        org.junit.jupiter.api.Assertions.assertEquals(11, stockOf(bat));
        postJson("/api/v1/inventory/stock", stockBody(bat, -12, null, null)).andExpect(status().isConflict());
        postJson("/api/v1/inventory/stock", stockBody(bat, 0, null, null)).andExpect(status().isBadRequest());

        // Editing moves the quantity (and can move it to another item).
        mockMvc.perform(put("/api/v1/inventory/stock/" + entry).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(stockBody(ball, 7, null, null)))).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(6, stockOf(bat));
        org.junit.jupiter.api.Assertions.assertEquals(7, stockOf(ball));

        // Deleting reverses it; issued units can't be reversed.
        mockMvc.perform(delete("/api/v1/inventory/stock/" + entry).cookie(admin)).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(0, stockOf(ball));
        postJson("/api/v1/inventory/stock", stockBody(ball, 3, null, null)).andExpect(status().isCreated());
        postJson("/api/v1/inventory/issues", issueBody(ball, 3)).andExpect(status().isCreated());
        String blocked = JSON.readTree(mockMvc.perform(get("/api/v1/inventory/stock").cookie(admin)).andReturn().getResponse()
                .getContentAsString()).get(0).get("id").asText();
        mockMvc.perform(delete("/api/v1/inventory/stock/" + blocked).cookie(admin)).andExpect(status().isConflict());
    }

    @Test
    void stockEntryDocumentCanBeAttachedDownloadedAndRemoved() throws Exception {
        String bat = addItem("Cricket Bat", 0);
        String entry = idOf(postJson("/api/v1/inventory/stock", stockBody(bat, 2, null, null)));
        var file = new org.springframework.mock.web.MockMultipartFile("file", "bill.pdf", "application/pdf", "%PDF-1.4 test".getBytes());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/inventory/stock/" + entry + "/document").file(file).cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attachment.fileName").value("bill.pdf"));
        mockMvc.perform(get("/api/v1/inventory/stock/" + entry + "/document").cookie(admin)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/inventory/stock/" + entry + "/document").cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attachment").doesNotExist());
        mockMvc.perform(get("/api/v1/inventory/stock/" + entry + "/document").cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/inventory/stock/" + entry).cookie(admin)).andExpect(status().isNoContent());
    }
}
