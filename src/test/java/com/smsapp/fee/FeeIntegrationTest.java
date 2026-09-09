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
 * Fee management against a real PostgreSQL instance with RLS. Covers the plan's
 * required security checks (section 7c/7d and 7.2f):
 * <ul>
 *   <li>tenant isolation on fee structures and invoices,</li>
 *   <li>only a SCHOOL_ADMIN may create fee structures / invoices,</li>
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

    private static final String SCHOOL_A = "fee-school-a";
    private static final String SCHOOL_B = "fee-school-b";
    private static final String ADMIN_A = "admin@fee-a.example";
    private static final String TEACHER_A = "teacher@fee-a.example";
    private static final String STUDENT_A = "student@fee-a.example";
    private static final String PARENT_A = "parent@fee-a.example";
    private static final String PARENT_A2 = "parent2@fee-a.example";
    private static final String ADMIN_B = "admin@fee-b.example";
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
    private UUID schoolBId;
    private UUID studentAId;      // tenant A, guardian = PARENT_A
    private UUID studentA2Id;     // tenant A, guardian = PARENT_A2
    private UUID studentBId;      // tenant B
    private UUID feeStructureAId;
    private UUID feeStructureBId;

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

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        schoolAId = UUID.randomUUID();
        schoolBId = UUID.randomUUID();
        studentAId = UUID.randomUUID();
        studentA2Id = UUID.randomUUID();
        studentBId = UUID.randomUUID();
        feeStructureAId = UUID.randomUUID();
        feeStructureBId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, audit_log CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolAId);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolBId);

            UUID adminRoleA = seedRole(st, tenantA, "SCHOOL_ADMIN");
            UUID teacherRoleA = seedRole(st, tenantA, "TEACHER");
            UUID studentRoleA = seedRole(st, tenantA, "STUDENT");
            UUID parentRoleA = seedRole(st, tenantA, "PARENT");
            UUID adminRoleB = seedRole(st, tenantB, "SCHOOL_ADMIN");

            seedUser(st, tenantA, ADMIN_A, adminRoleA);
            seedUser(st, tenantA, TEACHER_A, teacherRoleA);
            seedUser(st, tenantA, STUDENT_A, studentRoleA);
            UUID parentAId = seedUser(st, tenantA, PARENT_A, parentRoleA);
            UUID parentA2Id = seedUser(st, tenantA, PARENT_A2, parentRoleA);
            seedUser(st, tenantB, ADMIN_B, adminRoleB);

            seedStudent(st, tenantA, schoolAId, studentAId, "Anaya", parentAId);
            seedStudent(st, tenantA, schoolAId, studentA2Id, "Bhavya", parentA2Id);
            seedStudent(st, tenantB, schoolBId, studentBId, "Chandra", null);

            seedFeeStructure(st, tenantA, schoolAId, feeStructureAId, "Term 1 Tuition", "5000.00");
            seedFeeStructure(st, tenantB, schoolBId, feeStructureBId, "B Term Fee", "3000.00");
        }
    }

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, UUID schoolId)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
    }

    private static UUID seedRole(Statement st, UUID tenantId, String role) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, UUID tenantId, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '"
                + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
        return userId;
    }

    private static void seedStudent(Statement st, UUID tenantId, UUID schoolId, UUID studentId, String name,
                                    UUID guardianUserId) throws SQLException {
        st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status, "
                + "guardian_user_id) VALUES ('" + studentId + "', '" + tenantId + "', '" + schoolId + "', '" + name
                + "', 'ADM-" + name + "', 'ACTIVE', "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ")");
    }

    private static void seedFeeStructure(Statement st, UUID tenantId, UUID schoolId, UUID id, String name,
                                         String amount) throws SQLException {
        st.execute("INSERT INTO fee_structures (id, tenant_id, school_id, name, amount, due_date) VALUES ('"
                + id + "', '" + tenantId + "', '" + schoolId + "', '" + name + "', " + amount + ", '2026-06-01')");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
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

    private String orderPaidPayload(UUID tenantId, UUID invoiceId, String orderId, String paymentId) {
        return "{\"event\":\"order.paid\",\"payload\":{"
                + "\"payment\":{\"entity\":{\"id\":\"" + paymentId + "\",\"order_id\":\"" + orderId + "\"}},"
                + "\"order\":{\"entity\":{\"id\":\"" + orderId + "\",\"notes\":{"
                + "\"tenantId\":\"" + tenantId + "\",\"invoiceId\":\"" + invoiceId + "\"}}}}}";
    }

    private JsonNode invoicesForStudent(Cookie staff, UUID studentId) throws Exception {
        var result = mockMvc.perform(get("/api/v1/invoices").param("studentId", studentId.toString()).cookie(staff))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private UUID tenantIdOf(Cookie session) throws Exception {
        // decode the JWT payload segment of the access_token cookie
        String[] parts = session.getValue().split("\\.");
        JsonNode claims = JSON.readTree(java.util.Base64.getUrlDecoder().decode(parts[1]));
        return UUID.fromString(claims.get("tenant_id").asText());
    }

    // --- Fee structures: SCHOOL_ADMIN only + tenant isolation ------

    @Test
    void schoolAdminCanCreateAFeeStructure() throws Exception {
        mockMvc.perform(post("/api/v1/fee-structures").cookie(login(SCHOOL_A, ADMIN_A))
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
            mockMvc.perform(post("/api/v1/fee-structures").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void cannotCreateAFeeStructureForAnotherTenantsSchool() throws Exception {
        mockMvc.perform(post("/api/v1/fee-structures").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolId\":\"" + schoolBId + "\",\"name\":\"X\",\"amount\":10.00,"
                                + "\"dueDate\":\"2026-09-01\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void feeStructureListIsTenantScoped() throws Exception {
        mockMvc.perform(get("/api/v1/fee-structures").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Term 1 Tuition"));

        mockMvc.perform(get("/api/v1/fee-structures").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("B Term Fee"));
    }

    // --- Invoices: SCHOOL_ADMIN only + tenant isolation -----------

    @Test
    void schoolAdminCanGenerateAnInvoiceWithTheAmountFromTheFeeStructure() throws Exception {
        mockMvc.perform(post("/api/v1/invoices").cookie(login(SCHOOL_A, ADMIN_A))
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
        mockMvc.perform(post("/api/v1/invoices").cookie(login(SCHOOL_A, TEACHER_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentAId + "\",\"feeStructureId\":\"" + feeStructureAId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotGenerateAnInvoiceForAnotherTenantsStudent() throws Exception {
        mockMvc.perform(post("/api/v1/invoices").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentBId + "\",\"feeStructureId\":\"" + feeStructureAId + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cannotGenerateAnInvoiceAgainstAnotherTenantsFeeStructure() throws Exception {
        mockMvc.perform(post("/api/v1/invoices").cookie(login(SCHOOL_A, ADMIN_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentAId + "\",\"feeStructureId\":\"" + feeStructureBId + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listInvoicesIsStaffOnlyAndTenantScoped() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        createInvoiceA(admin, studentAId, feeStructureAId);

        mockMvc.perform(get("/api/v1/invoices").param("studentId", studentAId.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // STUDENT / PARENT may not use the staff endpoint.
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/invoices").param("studentId", studentAId.toString())
                            .cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }

        // Tenant B admin cannot list a tenant-A student's invoices.
        mockMvc.perform(get("/api/v1/invoices").param("studentId", studentAId.toString())
                        .cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isNotFound());
    }

    // --- Checkout: safe fields only, tenant isolation -------------

    @Test
    void checkoutCreatesAnOrderAndReturnsOnlyTheSafeFields() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
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
    void cannotCheckOutAnotherTenantsInvoice() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID invoiceId = createInvoiceA(adminA, studentAId, feeStructureAId);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/checkout").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aParentCanCheckOutTheirOwnChildsInvoiceButNotAnotherChilds() throws Exception {
        UUID childInvoice = createInvoiceA(login(SCHOOL_A, ADMIN_A), studentAId, feeStructureAId);
        UUID otherChildInvoice = createInvoiceA(login(SCHOOL_A, ADMIN_A), studentA2Id, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_PARENT");

        // PARENT_A is Anaya's (studentAId) guardian.
        mockMvc.perform(post("/api/v1/invoices/" + childInvoice + "/checkout").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razorpayOrderId").value("order_PARENT"));

        // Bhavya (studentA2Id) is PARENT_A2's child, not PARENT_A's -> 404, never leaks it exists.
        mockMvc.perform(post("/api/v1/invoices/" + otherChildInvoice + "/checkout").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());
    }

    // --- Webhook: signature verification (plan 7.2f) -------------

    @Test
    void webhookWithAValidSignatureMarksTheInvoicePaid() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_PAID1");
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/checkout").cookie(admin)).andExpect(status().isOk());

        String payload = orderPaidPayload(tenantIdOf(admin), invoiceId, "order_PAID1", "pay_PAID1");
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
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_TMP1");
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/checkout").cookie(admin)).andExpect(status().isOk());

        String signed = orderPaidPayload(tenantIdOf(admin), invoiceId, "order_TMP1", "pay_TMP1");
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
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        String payload = orderPaidPayload(tenantIdOf(admin), invoiceId, "order_NOSIG", "pay_NOSIG");

        mockMvc.perform(post("/api/v1/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(
                        invoicesForStudent(admin, studentAId).get(0).get("status").asText())
                .isEqualTo("PENDING");
    }

    @Test
    void webhookDeliveryIsIdempotent() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID invoiceId = createInvoiceA(admin, studentAId, feeStructureAId);
        when(razorpayGateway.createOrder(anyLong(), anyString(), anyString(), anyMap())).thenReturn("order_IDEM");
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/checkout").cookie(admin)).andExpect(status().isOk());

        String payload = orderPaidPayload(tenantIdOf(admin), invoiceId, "order_IDEM", "pay_IDEM");
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
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        createInvoiceA(admin, studentAId, feeStructureAId);

        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/invoices").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(5000.00))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void parentCannotSeeAnotherParentsChildsInvoicesSameTenant() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        createInvoiceA(admin, studentA2Id, feeStructureAId);

        // PARENT_A is not studentA2 (Bhavya)'s guardian -- PARENT_A2 is.
        mockMvc.perform(get("/api/v1/me/children/" + studentA2Id + "/invoices").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void parentInvoiceEndpointIsParentRoleOnly() throws Exception {
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/invoices").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isForbidden());
    }
}
