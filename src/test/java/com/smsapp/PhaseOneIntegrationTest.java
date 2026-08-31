package com.smsapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
            // The restricted app runtime role and its grants are provisioned by migration V4.
            // This connection is the elevated one, used only to seed cross-tenant fixture data.
            statement.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles CASCADE");
            statement.execute("INSERT INTO tenants (id, name, identifier) VALUES ('" + tenantA + "', 'Tenant A', '" + SCHOOL_A + "')");
            statement.execute("INSERT INTO tenants (id, name, identifier) VALUES ('" + tenantB + "', 'Tenant B', '" + SCHOOL_B + "')");
            // Tenant A: exactly 1 school, 1 user (the admin).
            statement.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('" + UUID.randomUUID() + "', '" + tenantA + "', 'School A')");
            statement.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('" + userA + "', '" + tenantA + "', 'admin@example.com', '" + passwordEncoder.encode("secret") + "', 'Admin A')");
            UUID roleId = UUID.randomUUID();
            statement.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('" + roleId + "', '" + tenantA + "', 'SCHOOL_ADMIN')");
            statement.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('" + userA + "', '" + roleId + "', '" + tenantA + "')");

            // Tenant B: deliberately more rows (3 schools, 3 users) so a query that
            // ignored tenant scoping would return larger counts than Tenant A's.
            for (int i = 0; i < 3; i++) {
                statement.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('" + UUID.randomUUID() + "', '" + tenantB + "', 'School B" + i + "')");
                statement.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('" + UUID.randomUUID() + "', '" + tenantB + "', 'user" + i + "@tenant-b.example', 'x', 'User B" + i + "')");
            }
        }
    }

    @Test
    void validLoginSetsHttpOnlyCookieAndTokenAuthenticatesViaCookieAndHeader() throws Exception {
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
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
    void loginSucceedsEvenWhenAStaleAccessTokenCookieIsPresent() throws Exception {
        // A browser re-sends the (possibly expired) access_token cookie with the
        // login request; that must not turn a valid sign-in into a 401.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .cookie(new Cookie("access_token", "stale.invalid.token"))
                        .content("{\"schoolIdentifier\":\"" + SCHOOL_A + "\",\"email\":\"admin@example.com\","
                                + "\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("access_token"));
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + SCHOOL_A + "\",\"email\":\"admin@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRejectsMissingJwt() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void applicationPoolConnectsAsRestrictedNonSuperuserRole() {
        // The autowired JdbcTemplate is backed by the application's own HikariCP pool.
        String currentUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        boolean isSuperuser = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT current_setting('is_superuser')::boolean", Boolean.class));
        boolean bypassRls = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class));

        assertThat(currentUser).isEqualTo("app_user_test");
        assertThat(isSuperuser).isFalse();
        assertThat(bypassRls).isFalse();
    }

    @Test
    void tenantACannotReadTenantBDataThroughDirectQuery() {
        // Runs on the application's own connection (restricted app_user role) -- no SET ROLE.
        Integer visibleSchools = new TransactionTemplate(transactionManager).execute(status ->
            jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
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
            }));

        assertThat(visibleSchools).isZero();
    }

    @Test
    void rlsAloneBlocksCrossTenantReadsAtRuntimeWithoutAnExplicitTenantFilter() {
        // Same query DashboardService issues for a SCHOOL_ADMIN, but deliberately WITHOUT the
        // WHERE tenant_id = ? clause. On the application's real runtime connection (restricted
        // app_user role, no SET ROLE), RLS alone must still scope the rows to Tenant A --
        // 1 school / 1 user -- and hide Tenant B's 3 + 3.
        int[] scoped = new TransactionTemplate(transactionManager).execute(status ->
                jdbcTemplate.execute((ConnectionCallback<int[]>) connection -> {
                    try (var setTenant = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant_id', ?, true)")) {
                        setTenant.setString(1, tenantA.toString());
                        setTenant.execute();
                    }
                    return new int[] {
                            singleCount(connection, "SELECT count(*) FROM schools"),
                            singleCount(connection, "SELECT count(*) FROM users")
                    };
                }));
        assertThat(scoped).containsExactly(1, 1);

        // With no tenant context set at all, the fail-safe policy exposes zero rows.
        Integer noContext = new TransactionTemplate(transactionManager).execute(status ->
                jdbcTemplate.queryForObject("SELECT count(*) FROM schools", Integer.class));
        assertThat(noContext).isZero();
    }

    @Test
    void dashboardSummaryReturnsTenantScopedCountsForSchoolAdmin() throws Exception {
        // Tenant B has 3 schools / 3 users seeded; the response must still show only Tenant A's.
        mockMvc.perform(get("/api/v1/dashboard/summary").cookie(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeholder").value(false))
                .andExpect(jsonPath("$.tenantId").value(tenantA.toString()))
                .andExpect(jsonPath("$.roles[0]").value("SCHOOL_ADMIN"))
                .andExpect(jsonPath("$.counts.schools").value(1))
                .andExpect(jsonPath("$.counts.users").value(1));
    }

    private Cookie login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + SCHOOL_A + "\",\"email\":\"admin@example.com\","
                                + "\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private static int singleCount(java.sql.Connection connection, String sql) throws java.sql.SQLException {
        try (var query = connection.prepareStatement(sql); var result = query.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }
}
