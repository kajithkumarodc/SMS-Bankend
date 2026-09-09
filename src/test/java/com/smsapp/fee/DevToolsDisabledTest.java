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
 * Guards the safety property of {@link DevToolsController}: with
 * {@code app.dev-tools-enabled} left at its default ({@code false}), the whole
 * {@code /api/v1/dev/**} surface must not exist -- an authenticated SCHOOL_ADMIN
 * hitting it gets a plain {@code 404}, and nothing about the invoice changes.
 *
 * <p>This is the test that makes it safe to ship: the payment side-channel cannot
 * silently be present in a deployed environment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevToolsDisabledTest {

    private static final String SCHOOL = "devtools-off";
    private static final String ADMIN = "admin@devtools-off.example";
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentId;
    private UUID feeStructureId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
        // app.dev-tools-enabled deliberately NOT set -> defaults to false.
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID schoolId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        feeStructureId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, audit_log CASCADE");

            st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                    + tenantId + "', 'Dev Off', '" + SCHOOL + "')");
            st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                    + schoolId + "', '" + tenantId + "', 'Dev Off School')");
            st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                    + roleId + "', '" + tenantId + "', 'SCHOOL_ADMIN')");
            st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                    + userId + "', '" + tenantId + "', '" + ADMIN + "', '" + passwordEncoder.encode(PASSWORD)
                    + "', '" + ADMIN + "')");
            st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                    + userId + "', '" + roleId + "', '" + tenantId + "')");
            st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status) VALUES ('"
                    + studentId + "', '" + tenantId + "', '" + schoolId + "', 'Anaya', 'ADM-Anaya', 'ACTIVE')");
            st.execute("INSERT INTO fee_structures (id, tenant_id, school_id, name, amount, due_date) VALUES ('"
                    + feeStructureId + "', '" + tenantId + "', '" + schoolId + "', 'Term 1', 5000.00, '2026-06-01')");
        }
    }

    private Cookie loginAsAdmin() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + SCHOOL + "\",\"email\":\"" + ADMIN
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID createInvoice(Cookie admin) throws Exception {
        var result = mockMvc.perform(post("/api/v1/invoices").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + studentId + "\",\"feeStructureId\":\"" + feeStructureId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void simulatePaymentEndpointIs404ForAnAuthenticatedAdminAndTheInvoiceStaysPending() throws Exception {
        Cookie admin = loginAsAdmin();
        UUID invoiceId = createInvoice(admin);

        mockMvc.perform(post("/api/v1/dev/invoices/" + invoiceId + "/simulate-payment-success").cookie(admin))
                .andExpect(status().isNotFound());

        // Nothing happened: the invoice is still PENDING, only the real webhook can change that.
        mockMvc.perform(get("/api/v1/invoices").param("studentId", studentId.toString()).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void theDevEndpointIs404EvenForARandomInvoiceId() throws Exception {
        mockMvc.perform(post("/api/v1/dev/invoices/" + UUID.randomUUID() + "/simulate-payment-success")
                        .cookie(loginAsAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void noDevRouteExistsAtAll() throws Exception {
        mockMvc.perform(post("/api/v1/dev/anything").cookie(loginAsAdmin()))
                .andExpect(status().isNotFound());
    }
}
