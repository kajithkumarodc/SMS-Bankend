package com.smsapp.fee;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Collect Fees line by line: partial payments, fines, validation, receipts, reversal, older payments, discounts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeeLineCollectionIntegrationTest {

    private static final String ADMIN = "lines-admin@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolId;
    private Cookie admin;
    private UUID sectionId;
    private UUID classId;

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
    void seed() throws Exception {
        schoolId = UUID.randomUUID();
        try (Connection connection = superuser(); Statement st = connection.createStatement()) {
            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, students, "
                    + "student_identifications, student_academic_history, student_documents, classes, sections, "
                    + "fee_structures, fee_structure_items, fee_discounts, invoices, invoice_lines, fee_payments, "
                    + "fee_payment_allocations, fee_adjustments CASCADE");
            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Lines School')");
            UUID userId = UUID.randomUUID();
            UUID roleId = UUID.randomUUID();
            st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + ADMIN
                    + "', '" + passwordEncoder.encode("secret") + "', 'Accounts Admin')");
            st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', 'SCHOOL_ADMIN')");
            st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
            for (String name : new String[]{"FEE_VIEW", "FEE_COLLECT", "FEE_REFUND", "FEE_DISCOUNT", "FEE_EDIT"}) {
                UUID permId = UUID.randomUUID();
                st.execute("INSERT INTO permissions (id, name) VALUES ('" + permId + "', '" + name + "')");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('" + roleId + "', '" + permId + "')");
            }
        }
        admin = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
        classId = id(send(post("/api/v1/classes"), "{\"schoolId\":\"" + schoolId + "\",\"name\":\"Class 1\"}", 201));
        sectionId = id(send(post("/api/v1/classes/" + classId + "/sections"), "{\"name\":\"A\"}", 201));
    }

    private JsonNode send(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        var result = mockMvc.perform(request.cookie(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
        String text = result.getResponse().getContentAsString();
        return text.isEmpty() ? null : JSON.readTree(text);
    }

    private JsonNode getJson(String url) throws Exception {
        return JSON.readTree(mockMvc.perform(get(url).cookie(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asText());
    }

    private static final String ITEMS = "[{\"category\":\"TERM_1\",\"label\":\"Tuition Fee\",\"amount\":1000.00,\"dueDate\":\"2026-06-10\"},"
            + "{\"category\":\"TERM_1\",\"label\":\"Uniform\",\"amount\":300.00,\"dueDate\":\"2026-06-10\"},"
            + "{\"category\":\"TERM_2\",\"label\":\"Tuition Fee\",\"amount\":500.00,\"dueDate\":\"2026-10-10\"}]";

    private UUID structure(String items) throws Exception {
        return id(send(post("/api/v1/fee-structures"), "{\"schoolId\":\"" + schoolId + "\",\"classId\":\"" + classId
                + "\",\"academicYear\":\"2026-2027\",\"name\":\"Class 1 Fees\",\"dueDate\":\"2026-06-10\","
                + "\"frequency\":\"ANNUAL\",\"items\":" + items + "}", 201));
    }

    private UUID student(String admissionNo) throws Exception {
        return id(send(post("/api/v1/students"), "{\"schoolId\":\"" + schoolId + "\",\"sectionId\":\"" + sectionId
                + "\",\"firstName\":\"Kid\",\"lastName\":\"" + admissionNo + "\",\"admissionNumber\":\"" + admissionNo
                + "\",\"dateOfBirth\":\"2019-01-01\"}", 201));
    }

    private UUID bill(UUID studentId, UUID structureId) throws Exception {
        return id(send(post("/api/v1/invoices"), "{\"studentId\":\"" + studentId + "\",\"feeStructureId\":\"" + structureId + "\"}", 201));
    }

    /** label/term -> line node */
    private Map<String, JsonNode> lines(UUID studentId) throws Exception {
        Map<String, JsonNode> map = new HashMap<>();
        getJson("/api/v1/students/" + studentId + "/fees").get("lines")
                .forEach(l -> map.put(l.get("label").asText() + "/" + l.get("term").asText(), l));
        return map;
    }

    private String collectBody(String date, String method, String linesJson) {
        return "{\"paymentDate\":\"" + date + "\",\"method\":\"" + method + "\",\"notes\":\"Term fees\",\"lines\":" + linesJson + "}";
    }

    @Test
    void collectsLineByLineWithFinesAndValidatesEveryAmount() throws Exception {
        UUID structureId = structure(ITEMS);
        UUID studentId = student("L-1");
        UUID invoiceId = bill(studentId, structureId);

        Map<String, JsonNode> before = lines(studentId);
        assertThat(before).hasSize(3);
        assertThat(before.values()).allMatch(l -> l.get("status").asText().equals("UNPAID"));
        String tuition1 = before.get("Tuition Fee/TERM_1").get("lineId").asText();
        String uniform = before.get("Uniform/TERM_1").get("lineId").asText();
        String today = LocalDate.now().toString();

        // Uniform in full, Tuition Term I partly with a fine, by UPI.
        JsonNode collected = send(post("/api/v1/students/" + studentId + "/fee-collections"), collectBody(today, "UPI",
                "[{\"invoiceLineId\":\"" + uniform + "\",\"amount\":300.00,\"fine\":0},"
                        + "{\"invoiceLineId\":\"" + tuition1 + "\",\"amount\":400.00,\"fine\":25.00}]"), 201);
        assertThat(collected.get("total").decimalValue()).isEqualByComparingTo("725.00");
        assertThat(collected.get("receiptNumbers")).hasSize(1);

        Map<String, JsonNode> after = lines(studentId);
        assertThat(after.get("Uniform/TERM_1").get("status").asText()).isEqualTo("PAID");
        JsonNode t1 = after.get("Tuition Fee/TERM_1");
        assertThat(t1.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(t1.get("paid").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(t1.get("fine").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(t1.get("balance").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(t1.get("payments").get(0).get("method").asText()).isEqualTo("UPI");
        JsonNode view = getJson("/api/v1/students/" + studentId + "/fees");
        assertThat(view.get("totals").get("paid").decimalValue()).isEqualByComparingTo("725.00");
        assertThat(view.get("totals").get("balance").decimalValue()).isEqualByComparingTo("1100.00");

        // More than is due, an already paid fee, a future date, an unknown mode, another student's fee.
        send(post("/api/v1/students/" + studentId + "/fee-collections"), collectBody(today, "CASH",
                "[{\"invoiceLineId\":\"" + tuition1 + "\",\"amount\":700.00}]"), 400);
        send(post("/api/v1/students/" + studentId + "/fee-collections"), collectBody(today, "CASH",
                "[{\"invoiceLineId\":\"" + uniform + "\",\"amount\":1.00}]"), 409);
        send(post("/api/v1/students/" + studentId + "/fee-collections"), collectBody(LocalDate.now().plusDays(1).toString(),
                "CASH", "[{\"invoiceLineId\":\"" + tuition1 + "\",\"amount\":10.00}]"), 400);
        send(post("/api/v1/students/" + studentId + "/fee-collections"), collectBody(today, "BITCOIN",
                "[{\"invoiceLineId\":\"" + tuition1 + "\",\"amount\":10.00}]"), 400);
        send(post("/api/v1/students/" + studentId + "/fee-collections"), collectBody(today, "CASH",
                "[{\"invoiceLineId\":\"" + tuition1 + "\",\"amount\":10.00},{\"invoiceLineId\":\"" + tuition1 + "\",\"amount\":10.00}]"), 400);
        UUID other = student("L-2");
        send(post("/api/v1/students/" + other + "/fee-collections"), collectBody(today, "CASH",
                "[{\"invoiceLineId\":\"" + tuition1 + "\",\"amount\":10.00}]"), 404);

        // The receipt lists what was paid.
        JsonNode receipt = getJson("/api/v1/fee-collections/" + collected.get("collectionId").asText());
        assertThat(receipt.get("lines")).hasSize(2);
        assertThat(receipt.get("total").decimalValue()).isEqualByComparingTo("725.00");
        assertThat(receipt.get("method").asText()).isEqualTo("UPI");
        assertThat(receipt.get("reversed").asBoolean()).isFalse();

        // Reversing it undoes both lines and the fine.
        String paymentId = t1.get("payments").get(0).get("paymentId").asText();
        send(post("/api/v1/payments/" + paymentId + "/reverse"), "{\"reason\":\"Entered twice\"}", 201);
        Map<String, JsonNode> reverted = lines(studentId);
        assertThat(reverted.values()).allMatch(l -> l.get("status").asText().equals("UNPAID"));
        assertThat(reverted.get("Tuition Fee/TERM_1").get("fine").decimalValue()).isEqualByComparingTo("0");
        JsonNode bills = JSON.readTree(mockMvc.perform(get("/api/v1/invoices").param("studentId", studentId.toString())
                .cookie(admin)).andReturn().getResponse().getContentAsString());
        assertThat(bills.get(0).get("paidAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(bills.get(0).get("netAmount").decimalValue()).isEqualByComparingTo("1800.00");
        assertThat(getJson("/api/v1/fee-collections/" + collected.get("collectionId").asText()).get("reversed").asBoolean()).isTrue();

        // A payment taken the old way (whole bill) is spread over the lines, earliest due first.
        send(post("/api/v1/invoices/" + invoiceId + "/payments"), "{\"amount\":1200.00,\"method\":\"CASH\"}", 201);
        Map<String, JsonNode> fifo = lines(studentId);
        assertThat(fifo.get("Tuition Fee/TERM_1").get("status").asText()).isEqualTo("PAID");
        assertThat(fifo.get("Uniform/TERM_1").get("paid").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(fifo.get("Tuition Fee/TERM_2").get("status").asText()).isEqualTo("UNPAID");
        // ...and the remaining lines can still be collected exactly to their balance.
        send(post("/api/v1/students/" + studentId + "/fee-collections"), collectBody(today, "CASH",
                "[{\"invoiceLineId\":\"" + uniform + "\",\"amount\":100.00},{\"invoiceLineId\":\""
                        + fifo.get("Tuition Fee/TERM_2").get("lineId").asText() + "\",\"amount\":500.00}]"), 201);
        assertThat(lines(studentId).values()).allMatch(l -> l.get("status").asText().equals("PAID"));
        assertThat(getJson("/api/v1/students/" + studentId + "/fees").get("groups").get(0).get("status").asText())
                .isEqualTo("PAID");
    }

    @Test
    void discountsAreSpreadOverLinesAndStructureChangesMoveTheRightLine() throws Exception {
        UUID structureId = structure(ITEMS);
        UUID studentId = student("L-3");
        UUID invoiceId = bill(studentId, structureId);

        JsonNode discount = send(post("/api/v1/fee-discounts"), "{\"name\":\"Sibling\",\"discountType\":\"FIXED\",\"value\":180.00}", 201);
        send(post("/api/v1/invoices/" + invoiceId + "/discount"), "{\"discountId\":\"" + discount.get("id").asText() + "\"}", 200);
        Map<String, JsonNode> discounted = lines(studentId);
        assertThat(discounted.get("Tuition Fee/TERM_1").get("discount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(discounted.get("Uniform/TERM_1").get("discount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(discounted.get("Tuition Fee/TERM_2").get("discount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(discounted.get("Tuition Fee/TERM_1").get("balance").decimalValue()).isEqualByComparingTo("900.00");

        // Fees Master raises Term II tuition by 100 and applies it to existing bills: only that line moves.
        String raised = ITEMS.replace("\"amount\":500.00", "\"amount\":600.00");
        JsonNode result = send(put("/api/v1/fee-structures/" + structureId + "?applyToExisting=true"),
                "{\"schoolId\":\"" + schoolId + "\",\"classId\":\"" + classId + "\",\"academicYear\":\"2026-2027\","
                        + "\"name\":\"Class 1 Fees\",\"dueDate\":\"2026-06-10\",\"frequency\":\"ANNUAL\",\"items\":" + raised + "}", 200);
        assertThat(result.get("adjustedInvoices").asInt()).isEqualTo(1);
        Map<String, JsonNode> changed = lines(studentId);
        assertThat(changed.get("Tuition Fee/TERM_2").get("amount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(changed.get("Tuition Fee/TERM_1").get("amount").decimalValue()).isEqualByComparingTo("1000.00");
        JsonNode view = getJson("/api/v1/students/" + studentId + "/fees");
        assertThat(view.get("totals").get("amount").decimalValue()).isEqualByComparingTo("1900.00");
        // Discount total unchanged (fixed) and still spread over the lines.
        assertThat(view.get("totals").get("discount").decimalValue()).isEqualByComparingTo("180.00");
        java.math.BigDecimal spread = java.math.BigDecimal.ZERO;
        for (JsonNode l : view.get("lines")) spread = spread.add(l.get("discount").decimalValue());
        assertThat(spread).isEqualByComparingTo("180.00");
    }

    @Test
    void admissionCanBillAdjustedLinesForOneStudent() throws Exception {
        UUID structureId = structure(ITEMS);
        UUID studentId = student("L-4");
        send(post("/api/v1/invoices"), "{\"studentId\":\"" + studentId + "\",\"feeStructureId\":\"" + structureId
                + "\",\"adjustmentReason\":\"Uniform bought\",\"lines\":["
                + "{\"label\":\"Tuition Fee\",\"category\":\"TERM_1\",\"dueDate\":\"2026-06-10\",\"amount\":1000.00},"
                + "{\"label\":\"Tuition Fee\",\"category\":\"TERM_2\",\"dueDate\":\"2026-10-10\",\"amount\":500.00}]}", 201);
        Map<String, JsonNode> lines = lines(studentId);
        assertThat(lines).hasSize(2).doesNotContainKey("Uniform/TERM_1");
        assertThat(getJson("/api/v1/students/" + studentId + "/fees").get("totals").get("amount").decimalValue())
                .isEqualByComparingTo("1500.00");
    }
}
