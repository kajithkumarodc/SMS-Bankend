package com.smsapp.school;

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

/** {@code GET /api/v1/schools} returns only the caller's tenant's schools (RLS + explicit filter). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SchoolDirectoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students CASCADE");
            st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('" + tenantA + "', 'A', 'dir-a')");
            st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('" + tenantB + "', 'B', 'dir-b')");
            st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('" + UUID.randomUUID() + "', '" + tenantA + "', 'Main Campus')");
            st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('" + UUID.randomUUID() + "', '" + tenantA + "', 'West Campus')");
            st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('" + UUID.randomUUID() + "', '" + tenantB + "', 'Other Tenant Campus')");
            st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('" + UUID.randomUUID()
                    + "', '" + tenantA + "', 'admin@dir-a.example', '" + passwordEncoder.encode("secret") + "', 'Admin A')");
        }
    }

    @Test
    void listsOnlyCallersTenantSchoolsOrderedByName() throws Exception {
        Cookie session = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"dir-a\",\"email\":\"admin@dir-a.example\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");

        mockMvc.perform(get("/api/v1/schools").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Main Campus"))
                .andExpect(jsonPath("$[1].name").value("West Campus"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/schools")).andExpect(status().isUnauthorized());
    }
}
