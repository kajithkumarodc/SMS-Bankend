package com.smsapp.auth;

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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/me/session}: a signed-in user picks up a permission granted after they logged in,
 * without the session getting any longer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionRefreshIntegrationTest {

    private static final String EMAIL = "session-refresh@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID roleId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
    }

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, visitors CASCADE");
            UUID userId = UUID.randomUUID();
            roleId = UUID.randomUUID();
            st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + EMAIL
                    + "', '" + passwordEncoder.encode("secret") + "', 'Front Desk')");
            st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', 'RECEPTIONIST')");
            st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), 'VISITOR_VIEW')");
        }
    }

    private void grantVisitorView() throws SQLException {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + roleId
                    + "', id FROM permissions WHERE name = 'VISITOR_VIEW'");
        }
    }

    /** The {@code exp} claim of a JWT, read without verifying it (the server already did). */
    private static long expiryOf(String jwt) throws Exception {
        JsonNode payload = JSON.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]),
                StandardCharsets.UTF_8));
        return payload.get("exp").asLong();
    }

    @Test
    void aPermissionGrantedAfterLoginIsPickedUpWithoutExtendingTheSession() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie original = login.getResponse().getCookie("access_token");
        mockMvc.perform(get("/api/v1/visitors").cookie(original)).andExpect(status().isForbidden());

        grantVisitorView();
        // Still baked into the old token until the session is refreshed.
        mockMvc.perform(get("/api/v1/visitors").cookie(original)).andExpect(status().isForbidden());

        MvcResult refreshed = mockMvc.perform(post("/api/v1/me/session").cookie(original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("Front Desk"))
                .andExpect(jsonPath("$.user.permissions[0]").value("VISITOR_VIEW"))
                .andReturn();
        Cookie updated = refreshed.getResponse().getCookie("access_token");

        assertThat(updated.getValue()).isNotEqualTo(original.getValue());
        assertThat(expiryOf(updated.getValue())).isEqualTo(expiryOf(original.getValue()));
        assertThat(updated.getMaxAge()).isPositive().isLessThanOrEqualTo(original.getMaxAge());
        mockMvc.perform(get("/api/v1/visitors").cookie(updated)).andExpect(status().isOk());
    }

    @Test
    void refreshingRequiresBeingSignedIn() throws Exception {
        mockMvc.perform(post("/api/v1/me/session")).andExpect(status().isUnauthorized());
    }
}
