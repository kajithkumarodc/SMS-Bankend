package com.smsapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.Cookie;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhaseOneIntegrationTest {
    // Temporary native PostgreSQL setup; switch this base back to Testcontainers when Docker is available for team isolation.

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final String SCHOOL_A = "school-a";
    private static final String SCHOOL_B = "school-b";

    private UUID tenantA;
    private UUID tenantB;
    private UUID userA;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seedTenants() throws SQLException {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        userA = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             var statement = connection.createStatement()) {
            statement.execute("DO $$ BEGIN CREATE ROLE app_user LOGIN PASSWORD 'app_pass'; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
            statement.execute("GRANT USAGE ON SCHEMA public TO app_user");
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_user");
            statement.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles CASCADE");
            statement.execute("INSERT INTO tenants (id, name, identifier) VALUES ('" + tenantA + "', 'Tenant A', '" + SCHOOL_A + "')");
            statement.execute("INSERT INTO tenants (id, name, identifier) VALUES ('" + tenantB + "', 'Tenant B', '" + SCHOOL_B + "')");
            statement.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('" + UUID.randomUUID() + "', '" + tenantA + "', 'School A')");
            statement.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('" + UUID.randomUUID() + "', '" + tenantB + "', 'School B')");
            statement.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('" + userA + "', '" + tenantA + "', 'admin@example.com', '" + passwordEncoder.encode("secret") + "', 'Admin A')");
            UUID roleId = UUID.randomUUID();
            statement.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('" + roleId + "', '" + tenantA + "', 'SCHOOL_ADMIN')");
            statement.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('" + userA + "', '" + roleId + "', '" + tenantA + "')");
        }
    }

    @Test
    void validLoginSetsHttpOnlyCookieAndTokenAuthenticatesViaCookieAndHeader() throws Exception {
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"schoolIdentifier\":\"" + SCHOOL_A + "\",\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.id").value(userA.toString()))
                .andExpect(jsonPath("$.user.name").value("Admin A"))
                .andExpect(jsonPath("$.user.tenantId").value(tenantA.toString()))
                .andExpect(jsonPath("$.user.roles[0]").value("SCHOOL_ADMIN"))
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().httpOnly("access_token", true))
                .andExpect(cookie().secure("access_token", true))
                .andExpect(cookie().path("access_token", "/"))
                .andExpect(cookie().maxAge("access_token", 3600))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
                .andReturn();

        Cookie authCookie = loginResult.getResponse().getCookie("access_token");
        assertThat(authCookie).isNotNull();

        String token = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(loginResult.getResponse().getContentAsString()).get("token").asText();

        // Cookie alone authenticates (no Authorization header).
        mockMvc.perform(get("/api/v1/me").cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userA.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantA.toString()));

        // Authorization header still works.
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userA.toString()));
    }

    @Test
    void logoutClearsAuthCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value("access_token", ""))
                .andExpect(cookie().maxAge("access_token", 0))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")));
    }

    @Test
    void invalidPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"schoolIdentifier\":\"" + SCHOOL_A + "\",\"email\":\"admin@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsMissingJwt() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantACannotReadTenantBDataThroughDirectQuery() {
        Integer visibleSchools = new TransactionTemplate(transactionManager).execute(status -> {
            return jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
                try (var role = connection.createStatement()) {
                    role.execute("SET ROLE app_user");
                }
                try (var setTenant = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                    setTenant.setString(1, tenantA.toString());
                    setTenant.execute();
                }
                try (var query = connection.prepareStatement("SELECT count(*) FROM schools WHERE tenant_id = ?")) {
                    query.setObject(1, tenantB);
                    try (var result = query.executeQuery()) {
                        result.next();
                        return result.getInt(1);
                    }
                }
            });
        });

        assertThat(visibleSchools).isZero();
    }
}
