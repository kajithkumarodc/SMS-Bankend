package com.smsapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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

    private UUID userA;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        userA = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             var statement = connection.createStatement()) {
            // The restricted app runtime role and its grants are provisioned by migration V4.
            // This connection is the elevated one, used only to seed fixture data.
            statement.execute("TRUNCATE schools, users, roles, permissions, user_roles CASCADE");
            statement.execute("INSERT INTO schools (id, name) VALUES ('" + UUID.randomUUID() + "', 'School A')");
            statement.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
                    + userA + "', 'admin@example.com', '" + passwordEncoder.encode("secret") + "', 'Admin A')");
            UUID roleId = UUID.randomUUID();
            statement.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', 'SCHOOL_ADMIN')");
            statement.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userA + "', '" + roleId + "')");
        }
    }

    @Test
    void validLoginSetsHttpOnlyCookieAndTokenAuthenticatesViaCookieAndHeader() throws Exception {
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.id").value(userA.toString()))
                .andExpect(jsonPath("$.user.name").value("Admin A"))
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
                .andExpect(jsonPath("$.userId").value(userA.toString()));

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
                        .content("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
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
                        .content("{\"email\":\"admin@example.com\",\"password\":\"wrong\"}"))
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
    void refreshRotatesTheTokenAndTheOldOneCanNeverBeUsedAgain() throws Exception {
        String refreshToken = loginAndGetRefreshToken();

        var rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn();

        String newRefreshToken = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(rotated.getResponse().getContentAsString()).get("refreshToken").asText();
        assertThat(newRefreshToken).isNotEqualTo(refreshToken);

        // The new token works once...
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefreshToken + "\"}"))
                .andExpect(status().isOk());

        // ...but the ORIGINAL (now-rotated) token must never work again, even once.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reusingARevokedRefreshTokenRevokesEveryOtherActiveTokenForThatUser() throws Exception {
        // Two "devices" logged in as the same user -- two independent refresh tokens.
        String deviceOneToken = loginAndGetRefreshToken();
        String deviceTwoToken = loginAndGetRefreshToken();

        // Device one refreshes normally, rotating its token.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + deviceOneToken + "\"}"))
                .andExpect(status().isOk());

        // An attacker replays device one's now-stale (already-rotated) token. This
        // must not only be rejected -- it must revoke every OTHER active refresh
        // token for the user too (including device two's, which never leaked),
        // because a replayed token is a signal the raw value escaped this user's
        // control and every outstanding session must be forced to re-authenticate.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + deviceOneToken + "\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + deviceTwoToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesOnlyItsOwnRefreshTokenNotTheUsersOtherSessions() throws Exception {
        String sessionAToken = loginAndGetRefreshToken();
        String sessionBToken = loginAndGetRefreshToken();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + sessionAToken + "\"}"))
                .andExpect(status().isNoContent());

        // Confirmed via the DB directly rather than by calling /refresh with
        // sessionAToken: presenting an already-revoked token there is exactly the
        // reuse scenario covered by reusingARevokedRefreshTokenRevokesEveryOther...
        // below, and would itself cascade-revoke session B -- which would make
        // this test pass for the wrong reason no matter what logout() actually did.
        Integer revokedCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Integer.class);
        assertThat(revokedCount).isEqualTo(1);

        // Session B was never touched by session A's logout.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + sessionBToken + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutAllRevokesEverySessionsRefreshToken() throws Exception {
        String sessionAToken = loginAndGetRefreshToken();
        String sessionBToken = loginAndGetRefreshToken();
        Cookie accessCookie = login();

        mockMvc.perform(post("/api/v1/auth/logout-all").cookie(accessCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + sessionAToken + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + sessionBToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String loginAndGetRefreshToken() throws Exception {
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(loginResult.getResponse().getContentAsString()).get("refreshToken").asText();
    }

    @Test
    void dashboardSummaryReturnsCountsForSchoolAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary").cookie(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeholder").value(false))
                .andExpect(jsonPath("$.roles[0]").value("SCHOOL_ADMIN"))
                .andExpect(jsonPath("$.counts.schools").value(1))
                .andExpect(jsonPath("$.counts.users").value(1));
    }

    private Cookie login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }
}
