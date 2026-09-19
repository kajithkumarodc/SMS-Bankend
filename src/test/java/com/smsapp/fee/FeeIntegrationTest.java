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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fee management against a real PostgreSQL instance. Covers the plan's required
 * security checks (section 7c/7d and 7.2f):
 * <ul>
 *   <li>only a SCHOOL_ADMIN may create fee structures / invoices,</li>
 *   <li>a nonexistent school/student/fee-structure/invoice reference is 404,</li>
 *   <li>the Razorpay webhook rejects tampered / unsigned payloads and only a
 *       signature-verified event moves an invoice to PAID,</li>
 *   <li>ownership isolation on the parent invoice endpoint.</li>
 * </ul>
 * The Razorpay HTTP client is mocked -- no test ever calls the real gateway --
 * but the webhook signature is verified for real against a test secret.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeeIntegrationTest {

    private static final String ADMIN_A = "admin@fee-a.example";
    private static final String TEACHER_A = "teacher@fee-a.example";
    private static final String STUDENT_A = "student@fee-a.example";
    private static final String PARENT_A = "parent@fee-a.example";
    private static final String PARENT_A2 = "parent2@fee-a.example";
    private static final String PASSWORD = "secret";
    private static final String WEBHOOK_SECRET = "whsec_integration_test_secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private RazorpayGateway razorpayGateway;

    private UUID schoolAId;
    private UUID studentAId;      // guardian = PARENT_A
    private UUID studentA2Id;     // guardian = PARENT_A2
    private UUID feeStructureAId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
        registry.add("razorpay.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("razorpay.key-id", () -> "rzp_test_integration");
    }

    @BeforeEach
    void seed() throws SQLException {
        when(razorpayGateway.keyId()).thenReturn("rzp_test_integration");

        schoolAId = UUID.randomUUID();
        studentAId = UUID.randomUUID();
        studentA2Id = UUID.randomUUID();
        feeStructureAId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, audit_log CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolAId + "', 'Tenant A School')");

            UUID adminRoleA = seedRole(st, "SCHOOL_ADMIN");
            UUID teacherRoleA = seedRole(st, "TEACHER");
            UUID studentRoleA = seedRole(st, "STUDENT");
            UUID parentRoleA = seedRole(st, "PARENT");

            seedUser(st, ADMIN_A, adminRoleA);
            seedUser(st, TEACHER_A, teacherRoleA);
            seedUser(st, STUDENT_A, studentRoleA);
            UUID parentAId = seedUser(st, PARENT_A, parentRoleA);
            UUID parentA2Id = seedUser(st, PARENT_A2, parentRoleA);

            seedStudent(st, schoolAId, studentAId, "Anaya", parentAId);
            seedStudent(st, schoolAId, studentA2Id, "Bhavya", parentA2Id);

            seedFeeStructure(st, schoolAId, feeStructureAId, "Term 1 Tuition", "5000.00");
        }
    }

    private static UUID seedRole(Statement st, String role) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '" + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return userId;
    }

    private static void seedStudent(Statement st, UUID schoolId, UUID studentId, String name, UUID guardianUserId)
            throws SQLException {
        st.execute("INSERT INTO students (id, school_id, full_name, admission_number, status, "
                + "guardian_user_id) VALUES ('" + studentId + "', '" + schoolId + "', '" + name
                + "', 'ADM-" + name + "', 'ACTIVE', "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ")");
    }

    private static void seedFeeStructure(Statement st, UUID schoolId, UUID id, String name, String amount)
            throws SQLException {
        st.execute("INSERT INTO fee_structures (id, school_id, name, amount, due_date) VALUES ('"
                + id + "', '" + schoolId + "', '" + name + "', " + amount + ", '2026-06-01')");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID createInvoiceA(Cookie admin, UUID studentId, UUID feeStructureId) throws Exception {
        var result = mockMvc.perform(post("/api/v1/invoices").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"feeStructureId\":\"" + feeStructureId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String hmacHex(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String orderPaidPayload(UUID invoiceId, String orderId, String paymentId) {
        return "{\"event\":\"order.paid\",\"payload\":{"
                + "\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"order_id\":\"" + orderId + "\"}},"
                + "\"order\":{\"entity\":{\"id\":\"" + orderId + "\",\"notes\":{"
                + "\"invoiceId\":\"" + invoiceId + "\"}}}}}";
    }

    private JsonNode invoicesForStudent(Cookie staff, UUID studentId) throws Exception {
        var result = mockMvc.perform(get("/api/v1/invoices").param("studentId", studentId.toString()).cookie(staff))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // --- Fee structures: SCHOOL_ADMIN only -------------------------

    @Test
    void schoolAdminCanCreateAFeeStructure() throws Exception {
        mockMvc.perform(post("/api/v1/fee-structures").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolAId + "\",\"name\":\"Term 2 Tuition\","
                                + "\"amount\":6000.00,\"dueDate\":\"2026-09-01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Term 2 Tuition"))
                .andExpect(jsonPath("$.amount").value(6000.00));
    }

    @Test
    void teacherStudentAndParentCannotCreateAFeeStructure() throws Exception {
        String body = "{\"schoolId\":\"" + schoolAId + "\",\"name\":\"X\",\"amount\":10.00,\"dueDate\":\"2026-09-01\"}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/fee-structures").cookie(login(email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void cannotCreateAFeeStructureForANonexistentSchool() throws Exception {
        mockMvc.perform(post("/api/v1/fee-structures").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + UUID.randomUUID() + "\",\"name\":\"X\",\"amount\":10.00,"
                                + "\"dueDate\":\"2026-09-01\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void feeStructureListReturnsCreatedStructures() throws Exception {
        mockMvc.perform(get("/api/v1/fee-structures").cookie(login(ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Term 1 Tuition"));
    }

    // --- Invoices: SCHOOL_ADMIN only -------------------------------

    @Test
    void schoolAdminCanGenerateAnInvoiceWithTheAmountFromTheFeeStructure() throws Exception {
        mockMvc.perform(post("/api/v1/invoices").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentAId + "\",\"feeStructureId\":\"" + feeStructureAId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(5000.00))
                .andExpect(jsonPath("$.studentId").value(studentAId.toString()))
                .andExpect(jsonPath("$.razorpayOrderId").doesNotExist());
    }

    @Test
    void teacherCannotGenerateAnInvoice() throws Exception {
        mockMvc.perform(post("/api/v1/invoices").cookie(login(TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentAId + "\",\"feeStructureId\":\"" + feeStructureAId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotGenerateAnInvoiceForANonexistentStudent() throws Exception {
        mockMvc.perform(post("/api/v1/invoices").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + UUID.randomUUID() + "\",\"feeStructureId\":\"" + feeStructureAId + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotGenerateAnInvoiceAgainstANonexistentFeeStructure() throws Exception {
        mockMvc.perform(post("/api/v1/invoices").cookie(login(ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentAId + "\",\"feeStructureId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listInvoicesIsStaffOnly() throws Exception {
        Cookie admin = login(ADMIN_A);
        createInvoiceA(admin, studentAId, feeStructureAId);

        mockMvc.perform(get("/api/v1/invoices").param("studentId", studentAId.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // STUDENT / PARENT may not use the staff endpoint.
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/invoices").param("studentId", studentAId.toString())
                            .cookie(login(email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Checkout: safe fields only ---------------------------------

    @Test
    void checkoutCreatesAnOrderAndReturnsOnlyTheSafeFields() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), eq("INR"), anyString(), anyMap())).thenReturn("order_CHK1");

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/checkout").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razorpayOrderId").value("order_CHK1"))
                .andExpect(jsonPath("$.razorpayKeyId").value("rzp_test_integration"))
                .andExpect(jsonPath("$.amountInPaise").value(500000))
                .andExpect(jsonPath("$.currency").value("INR"));

        // The order id is now persisted on the invoice.
        JsonNode invoices = invoicesForStudent(admin, studentAId);
        org.assertj.core.api.Assertions.assertThat(invoices.get(0).get("razorpayOrderId").asText())
                .isEqualTo("order_CHK1");
    }

    @Test
    void checkoutForANonexistentInvoiceReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/invoices/" + UUID.randomUUID() + "/checkout").cookie(login(ADMIN_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aParentCanCheckOutTheirOwnChildsInvoiceButNotAnotherChilds() throws Exception {
        UUID childInvoice = createInvoiceA(login(ADMIN_A), studentAId, feeStructureAId);
        UUID otherChildInvoice = createInvoiceA(login(ADMIN_A), studentA2Id, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_PARENT");

        // PARENT_A is Anaya's (studentAId) guardian.
        mockMvc.perform(post("/api/v1/invoices/" + childInvoice + "/checkout").cookie(login(PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razorpayOrderId").value("order_PARENT"));

        // Bhavya (studentA2Id) is PARENT_A2's child, not PARENT_A's -> 404, never leaks it exists.
        mockMvc.perform(post("/api/v1/invoices/" + otherChildInvoice + "/checkout").cookie(login(PARENT_A)))
                .andExpect(status().isNotFound());
    }

    // --- Webhook: signature verification (plan 7.2f) -------------

    @Test
    void webhookWithAValidSignatureMarksTheInvoicePaid() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_PAID1");
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/checkout").cookie(admin)).andExpect(status().isOk());

        String payload = orderPaidPayload(invoiceId, "order_PAID1", "pay_PAID1");
        mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .header("X-Razorpay-Signature", hmacHex(payload))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());

        JsonNode invoices = invoicesForStudent(admin, studentAId);
        org.assertj.core.api.Assertions.assertThat(invoices.get(0).get("status").asText()).isEqualTo("PAID");
        org.assertj.core.api.Assertions.assertThat(invoices.get(0).get("razorpayPaymentId").asText())
                .isEqualTo("pay_PAID1");
        org.assertj.core.api.Assertions.assertThat(invoices.get(0).get("paidAt").isNull()).isFalse();
    }

    @Test
    void webhookWithATamperedPayloadIsRejectedAndTheInvoiceStaysPending() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_TMP1");
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/checkout").cookie(admin)).andExpect(status().isOk());

        String signed = orderPaidPayload(invoiceId, "order_TMP1", "pay_TMP1");
        String signature = hmacHex(signed);
        // Attacker keeps the captured signature but swaps in a different invoice/order.
        String tampered = signed.replace("pay_TMP1", "pay_ATTACKER");

        mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .header("X-Razorpay-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(
                        invoicesForStudent(admin, studentAId).get(0).get("status").asText())
                .isEqualTo("PENDING");
    }

    @Test
    void webhookWithNoSignatureHeaderIsRejected() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        String payload = orderPaidPayload(invoiceId, "order_NOSIG", "pay_NOSIG");

        mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(
                        invoicesForStudent(admin, studentAId).get(0).get("status").asText())
                .isEqualTo("PENDING");
    }

    @Test
    void webhookDeliveryIsIdempotent() throws Exception {
        Cookie admin = login(ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_IDEM");
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/checkout").cookie(admin)).andExpect(status().isOk());

        String payload = orderPaidPayload(invoiceId, "order_IDEM", "pay_IDEM");
        String signature = hmacHex(payload);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/webhooks/razorpay").header("X-Razorpay-Signature", signature)
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isOk());
        }

        JsonNode invoices = invoicesForStudent(admin, studentAId);
        org.assertj.core.api.Assertions.assertThat(invoices.get(0).get("status").asText()).isEqualTo("PAID");
        org.assertj.core.api.Assertions.assertThat(invoices.get(0).get("razorpayPaymentId").asText())
                .isEqualTo("pay_IDEM");
    }

    // --- Parent invoice endpoint: ownership isolation ------------

    @Test
    void parentSeesOnlyTheirOwnChildsInvoices() throws Exception {
        Cookie admin = login(ADMIN_A);
        createInvoiceA(admin, studentAId, feeStructureAId);

        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/invoices").cookie(login(PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(5000.00))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void parentCannotSeeAnotherParentsChildsInvoices() throws Exception {
        Cookie admin = login(ADMIN_A);
        createInvoiceA(admin, studentA2Id, feeStructureAId);

        // PARENT_A is not studentA2 (Bhavya)'s guardian -- PARENT_A2 is.
        mockMvc.perform(get("/api/v1/me/children/" + studentA2Id + "/invoices").cookie(login(PARENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void parentInvoiceEndpointIsParentRoleOnly() throws Exception {
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/invoices").cookie(login(ADMIN_A)))
                .andExpect(status().isForbidden());
    }
}
