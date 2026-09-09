package com.smsapp.fee;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dev-tools payment simulator WITH {@code app.dev-tools-enabled=true}: it
 * flips an invoice to PAID exactly as the verified webhook would. A SCHOOL_ADMIN
 * may do it for any invoice in the tenant; a PARENT only for their own child's
 * (so the parent-facing "Pay Now" demo can stand in for the webhook locally).
 * Tenant-scoped and idempotent. (The default-disabled 404 behaviour is covered
 * by {@link DevToolsDisabledTest}.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.dev-tools-enabled=true")
class DevToolsIntegrationTest {

    private static final String SCHOOL_A = "devtools-a";
    private static final String SCHOOL_B = "devtools-b";
    private static final String ADMIN_A = "admin@devtools-a.example";
    private static final String TEACHER_A = "teacher@devtools-a.example";
    private static final String PARENT_A = "parent@devtools-a.example";        // guardian of studentAId
    private static final String OTHER_PARENT_A = "parent2@devtools-a.example";  // guardian of otherStudentAId
    private static final String ADMIN_B = "admin@devtools-b.example";
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentAId;
    private UUID otherStudentAId;
    private UUID feeStructureAId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID schoolAId = UUID.randomUUID();
        UUID schoolBId = UUID.randomUUID();
        studentAId = UUID.randomUUID();
        otherStudentAId = UUID.randomUUID();
        feeStructureAId = UUID.randomUUID();

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

            seedUser(st, tenantA, ADMIN_A, seedRole(st, tenantA, "SCHOOL_ADMIN"));
            seedUser(st, tenantA, TEACHER_A, seedRole(st, tenantA, "TEACHER"));
            UUID parentRoleA = seedRole(st, tenantA, "PARENT");
            UUID parentAId = seedUser(st, tenantA, PARENT_A, parentRoleA);
            UUID otherParentAId = seedUser(st, tenantA, OTHER_PARENT_A, parentRoleA);
            seedUser(st, tenantB, ADMIN_B, seedRole(st, tenantB, "SCHOOL_ADMIN"));

            st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status, "
                    + "guardian_user_id) VALUES ('" + studentAId + "', '" + tenantA + "', '" + schoolAId
                    + "', 'Anaya', 'ADM-Anaya', 'ACTIVE', '" + parentAId + "')");
            st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status, "
                    + "guardian_user_id) VALUES ('" + otherStudentAId + "', '" + tenantA + "', '" + schoolAId
                    + "', 'Bhavya', 'ADM-Bhavya', 'ACTIVE', '" + otherParentAId + "')");
            st.execute("INSERT INTO fee_structures (id, tenant_id, school_id, name, amount, due_date) VALUES ('"
                    + feeStructureAId + "', '" + tenantA + "', '" + schoolAId + "', 'Term 1', 5000.00, '2026-06-01')");
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
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD)
                + "', '" + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
        return userId;
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID createInvoiceA(Cookie admin) throws Exception {
        return createInvoice(admin, studentAId);
    }

    private UUID createInvoice(Cookie admin, UUID studentId) throws Exception {
        var result = mockMvc.perform(post("/api/v1/invoices").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"feeStructureId\":\"" + feeStructureAId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private String statusOf(Cookie admin, UUID invoiceId) throws Exception {
        var result = mockMvc.perform(get("/api/v1/invoices").param("studentId", studentAId.toString()).cookie(admin))
                .andExpect(status().isOk()).andReturn();
        var arr = JSON.readTree(result.getResponse().getContentAsString());
        for (var node : arr) {
            if (node.get("id").asText().equals(invoiceId.toString())) {
                return node.get("status").asText();
            }
        }
        return null;
    }

    @Test
    void adminSimulatesPaymentAndTheInvoiceBecomesPaid() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID invoiceId = createInvoiceA(admin);

        mockMvc.perform(post("/api/v1/dev/invoices/" + invoiceId + "/simulate-payment-success").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invoiceId.toString()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());

        org.assertj.core.api.Assertions.assertThat(statusOf(admin, invoiceId)).isEqualTo("PAID");
    }

    @Test
    void simulatingIsIdempotent() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID invoiceId = createInvoiceA(admin);
        String url = "/api/v1/dev/invoices/" + invoiceId + "/simulate-payment-success";

        mockMvc.perform(post(url).cookie(admin)).andExpect(status().isOk());
        mockMvc.perform(post(url).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void aTeacherCannotSimulatePayment() throws Exception {
        UUID invoiceId = createInvoiceA(login(SCHOOL_A, ADMIN_A));

        mockMvc.perform(post("/api/v1/dev/invoices/" + invoiceId + "/simulate-payment-success")
                        .cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotSimulatePaymentForAnotherTenantsInvoice() throws Exception {
        UUID invoiceId = createInvoiceA(login(SCHOOL_A, ADMIN_A));

        mockMvc.perform(post("/api/v1/dev/invoices/" + invoiceId + "/simulate-payment-success")
                        .cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/dev/invoices/" + UUID.randomUUID() + "/simulate-payment-success"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aParentCanSimulatePaymentForTheirOwnChildsInvoice() throws Exception {
        UUID invoiceId = createInvoice(login(SCHOOL_A, ADMIN_A), studentAId);

        mockMvc.perform(post("/api/v1/dev/invoices/" + invoiceId + "/simulate-payment-success")
                        .cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void aParentCannotSimulatePaymentForAnotherFamilysInvoice() throws Exception {
        // Bhavya (otherStudentAId) is OTHER_PARENT_A's child, not PARENT_A's -> 404, same tenant.
        UUID invoiceId = createInvoice(login(SCHOOL_A, ADMIN_A), otherStudentAId);

        mockMvc.perform(post("/api/v1/dev/invoices/" + invoiceId + "/simulate-payment-success")
                        .cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());
    }
}
