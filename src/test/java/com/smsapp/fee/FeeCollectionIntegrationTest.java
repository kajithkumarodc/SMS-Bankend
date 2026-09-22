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

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fee assignment/discount/collection/refund against a real PostgreSQL instance (plan Phase 5 parts
 * C-G, N, P). {@code /invoices/bulk-assign}, {@code /fee-discounts}, {@code /invoices/{id}/payments}
 * and {@code /payments/{id}/reverse} are new endpoints, so -- same fix as
 * {@code StudentAdmissionIntegrationTest}/{@code AcademicYearIntegrationTest} -- the FEE_* permission
 * catalog is self-seeded here since the migration-seeded one only exists once and every
 * {@code @BeforeEach} in this suite truncates roles/permissions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeeCollectionIntegrationTest {

    private static final String ADMIN = "fc-admin@school.example";
    private static final String ACCOUNTANT = "fc-accountant@school.example";
    private static final String TEACHER = "fc-teacher@school.example";
    private static final String PARENT_A = "fc-parent-a@school.example";
    private static final String PARENT_B = "fc-parent-b@school.example";
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID schoolId;
    private UUID studentAId;
    private UUID studentBId;
    private UUID feeStructureId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        schoolId = UUID.randomUUID();
        studentAId = UUID.randomUUID();
        studentBId = UUID.randomUUID();
        feeStructureId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE schools, users, roles, permissions, user_roles, role_permissions, students, "
                    + "invoices, fee_structures, fee_discounts, fee_types, audit_log CASCADE");

            st.execute("INSERT INTO schools (id, name) VALUES ('" + schoolId + "', 'Fee Collection School')");

            UUID adminRoleId = seedRole(st, "SCHOOL_ADMIN");
            UUID accountantRoleId = seedRole(st, "ACCOUNTANT");
            UUID teacherRoleId = seedRole(st, "TEACHER");
            UUID parentRoleId = seedRole(st, "PARENT");

            UUID adminUserId = seedUser(st, ADMIN, adminRoleId);
            seedUser(st, ACCOUNTANT, accountantRoleId);
            seedUser(st, TEACHER, teacherRoleId);
            UUID parentAUserId = seedUser(st, PARENT_A, parentRoleId);
            UUID parentBUserId = seedUser(st, PARENT_B, parentRoleId);

            st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, "
                    + "status, guardian_user_id) VALUES ('" + studentAId + "', '" + schoolId
                    + "', 'Student A', 'Student', 'A', 'ADM-FC-A', 'ACTIVE', '" + parentAUserId + "')");
            st.execute("INSERT INTO students (id, school_id, full_name, first_name, last_name, admission_number, "
                    + "status, guardian_user_id) VALUES ('" + studentBId + "', '" + schoolId
                    + "', 'Student B', 'Student', 'B', 'ADM-FC-B', 'ACTIVE', '" + parentBUserId + "')");

            st.execute("INSERT INTO fee_structures (id, school_id, name, amount, due_date, academic_year) "
                    + "VALUES ('" + feeStructureId + "', '" + schoolId
                    + "', 'Term 1 Tuition', 1000.00, '2020-01-01', '2026-2027')");

            // FEE_* permissions: SCHOOL_ADMIN gets everything this suite needs; ACCOUNTANT gets
            // collection/discount/assign but deliberately NOT refund, mirroring V26's real default.
            String[] adminOnly = {"FEE_REFUND"};
            String[] shared = {"FEE_VIEW", "FEE_CREATE", "FEE_ASSIGN", "FEE_COLLECT", "FEE_DISCOUNT", "FEE_EDIT"};
            for (String name : shared) {
                UUID permId = grantPermission(st, name, adminRoleId);
                grantTo(st, permId, accountantRoleId);
            }
            for (String name : adminOnly) {
                grantPermission(st, name, adminRoleId);
            }
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

    private static UUID grantPermission(Statement st, String name, UUID roleId) throws SQLException {
        UUID permId = UUID.randomUUID();
        st.execute("INSERT INTO permissions (id, name) VALUES ('" + permId + "', '" + name + "')");
        grantTo(st, permId, roleId);
        return permId;
    }

    private static void grantTo(Statement st, UUID permId, UUID roleId) throws SQLException {
        st.execute("INSERT INTO role_permissions (role_id, permission_id) VALUES ('" + roleId + "', '" + permId + "')");
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID assignInvoice(Cookie admin, UUID studentId) throws Exception {
        var result = mockMvc.perform(post("/api/v1/invoices").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"feeStructureId\":\"" + feeStructureId + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Bulk assignment -------------------------------------------------

    @Test
    void bulkAssignSkipsAlreadyInvoicedStudentsOnASecondCall() throws Exception {
        Cookie admin = login(ADMIN);
        String body = "{\"feeStructureId\":\"" + feeStructureId + "\",\"studentIds\":[\"" + studentAId + "\",\"" + studentBId + "\"]}";

        mockMvc.perform(post("/api/v1/invoices/bulk-assign").cookie(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(2));

        mockMvc.perform(post("/api/v1/invoices/bulk-assign").cookie(admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCount").value(0))
                .andExpect(jsonPath("$.results[0].reason").value("Already assigned this fee"));
    }

    @Test
    void duplicateSingleInvoiceAssignmentIsRejectedWith409() throws Exception {
        Cookie admin = login(ADMIN);
        assignInvoice(admin, studentAId);

        mockMvc.perform(post("/api/v1/invoices").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentAId + "\",\"feeStructureId\":\"" + feeStructureId + "\"}"))
                .andExpect(status().isConflict());
    }

    // --- Authorization: new endpoints are permission-gated ---------------

    @Test
    void teacherCannotAssignDiscountOrCollect() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId);
        Cookie teacher = login(TEACHER);

        mockMvc.perform(post("/api/v1/invoices/bulk-assign").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feeStructureId\":\"" + feeStructureId + "\",\"studentIds\":[\"" + studentBId + "\"]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(teacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"method\":\"CASH\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/fee-discounts").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"discountType\":\"FIXED\",\"value\":10.00}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountantCanCollectButNotRefund() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId);
        Cookie accountant = login(ACCOUNTANT);

        var result = mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(accountant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000.00,\"method\":\"CASH\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID paymentId = UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/reverse").cookie(accountant)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    // --- Full collection workflow: discount, partial, full -----------

    @Test
    void discountThenPartialThenFullPaymentReachesPaidStatus() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId);

        var discountResult = mockMvc.perform(post("/api/v1/fee-discounts").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sibling Discount\",\"discountType\":\"FIXED\",\"value\":100.00}"))
                .andExpect(status().isCreated()).andReturn();
        UUID discountId = UUID.fromString(JSON.readTree(discountResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/discount").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountId\":\"" + discountId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountAmount").value(100.00))
                .andExpect(jsonPath("$.netAmount").value(900.00))
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":400.00,\"method\":\"CASH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNumber").exists());

        mockMvc.perform(get("/api/v1/students/" + studentAId + "/fee-statement").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoices[0].status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.invoices[0].balance").value(500.00));

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500.00,\"method\":\"BANK_TRANSFER\",\"referenceNumber\":\"TXN-1\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/students/" + studentAId + "/fee-statement").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoices[0].status").value("PAID"))
                .andExpect(jsonPath("$.invoices[0].balance").value(0.00))
                .andExpect(jsonPath("$.totalPaid").value(900.00));

        mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // --- Amount tampering / validation (plan part N) ----------------

    @Test
    void collectPaymentRejectsAnAmountGreaterThanTheOutstandingBalance() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId); // balance 1000.00

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5000.00,\"method\":\"CASH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("exceeds the outstanding balance")));

        mockMvc.perform(get("/api/v1/students/" + studentAId + "/fee-statement").cookie(admin))
                .andExpect(jsonPath("$.invoices[0].status").value("PENDING"));
    }

    @Test
    void collectPaymentRejectsZeroAndNegativeAmounts() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0,\"method\":\"CASH\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-50.00,\"method\":\"CASH\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotCollectAgainstAnAlreadyFullyPaidInvoice() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId);
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000.00,\"method\":\"CASH\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1.00,\"method\":\"CASH\"}"))
                .andExpect(status().isConflict());
    }

    // --- Refund / reversal ---------------------------------------------

    @Test
    void reversingAPaymentRestoresTheBalanceAndCannotBeReversedTwice() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId);
        var payResult = mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000.00,\"method\":\"CASH\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID paymentId = UUID.fromString(JSON.readTree(payResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/reverse").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Cheque bounced\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversesPaymentId").value(paymentId.toString()));

        mockMvc.perform(get("/api/v1/students/" + studentAId + "/fee-statement").cookie(admin))
                .andExpect(jsonPath("$.invoices[0].status").value("PENDING"))
                .andExpect(jsonPath("$.invoices[0].balance").value(1000.00));

        // Never physically deleted -- both the original payment and its reversal remain in the ledger.
        mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/reverse").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
    }

    // --- Receipt ---------------------------------------------------------

    @Test
    void receiptContainsTheExpectedSchoolStudentAndPaymentDetails() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId);
        var payResult = mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000.00,\"method\":\"CASH\",\"referenceNumber\":\"R-1\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID paymentId = UUID.fromString(JSON.readTree(payResult.getResponse().getContentAsString()).get("id").asText());

        mockMvc.perform(get("/api/v1/payments/" + paymentId + "/receipt").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schoolName").value("Fee Collection School"))
                .andExpect(jsonPath("$.studentName").value("Student A"))
                .andExpect(jsonPath("$.admissionNumber").value("ADM-FC-A"))
                .andExpect(jsonPath("$.feeStructureName").value("Term 1 Tuition"))
                .andExpect(jsonPath("$.originalAmount").value(1000.00))
                .andExpect(jsonPath("$.balanceAfter").value(0.00))
                .andExpect(jsonPath("$.payment.method").value("CASH"))
                .andExpect(jsonPath("$.payment.referenceNumber").value("R-1"));
    }

    // --- Parent portal: cross-family isolation (plan part J/N) -------

    @Test
    void parentSeesOnlyTheirOwnChildsFeeStatementAndReceipt() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceA = assignInvoice(admin, studentAId);
        assignInvoice(admin, studentBId);
        var payResult = mockMvc.perform(post("/api/v1/invoices/" + invoiceA + "/payments").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":250.00,\"method\":\"CASH\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID paymentAId = UUID.fromString(JSON.readTree(payResult.getResponse().getContentAsString()).get("id").asText());

        Cookie parentA = login(PARENT_A);
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/fee-statement").cookie(parentA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPaid").value(250.00));
        mockMvc.perform(get("/api/v1/me/payments/" + paymentAId + "/receipt").cookie(parentA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Student A"));

        // PARENT_A must never reach PARENT_B's child's statement or receipt, by student-ID or payment-ID manipulation.
        mockMvc.perform(get("/api/v1/me/children/" + studentBId + "/fee-statement").cookie(parentA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/me/payments/" + paymentAId + "/receipt").cookie(login(PARENT_B)))
                .andExpect(status().isNotFound());
    }

    // --- Concurrency (plan parts N/P) -------------------------------

    @Test
    void twoConcurrentCollectionAttemptsCannotBothSucceedAgainstTheSameBalance() throws Exception {
        Cookie admin = login(ADMIN);
        UUID invoiceId = assignInvoice(admin, studentAId); // balance 1000.00
        Callable<Integer> attempt = () -> mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000.00,\"method\":\"CASH\"}"))
                .andReturn().getResponse().getStatus();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            List<Integer> statuses = List.of(first.get(), second.get());

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdown();
        }

        mockMvc.perform(get("/api/v1/students/" + studentAId + "/fee-statement").cookie(admin))
                .andExpect(jsonPath("$.invoices[0].status").value("PAID"))
                .andExpect(jsonPath("$.invoices[0].paidAmount").value(1000.00))
                .andExpect(jsonPath("$.invoices[0].balance").value(0.00));
    }
}
