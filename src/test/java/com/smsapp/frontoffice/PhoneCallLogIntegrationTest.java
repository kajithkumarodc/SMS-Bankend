package com.smsapp.frontoffice;

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
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phone Call Log against a real PostgreSQL instance: PHONE_CALL_* gating, validation, search/sort, edit, delete. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhoneCallLogIntegrationTest {

    private static final String ADMIN = "pc-admin@school.example";
    private static final String RECEPTIONIST = "pc-receptionist@school.example";
    private static final String TEACHER = "pc-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, phone_call_logs CASCADE");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID receptionistRole = seedUser(st, RECEPTIONIST, "RECEPTIONIST");
            seedUser(st, TEACHER, "TEACHER");
            // Same grants V33 makes (other test classes truncate roles/permissions).
            grant(st, adminRole, "PHONE_CALL_VIEW", "PHONE_CALL_CREATE", "PHONE_CALL_EDIT", "PHONE_CALL_DELETE",
                    "PHONE_CALL_EXPORT", "PHONE_CALL_PRINT");
            grant(st, receptionistRole, "PHONE_CALL_VIEW", "PHONE_CALL_CREATE", "PHONE_CALL_EDIT");
        }
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return roleId;
    }

    private static void grant(Statement st, UUID roleId, String... permissions) throws SQLException {
        for (String name : permissions) {
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + roleId + "', id FROM permissions "
                    + "WHERE name = '" + name + "'");
        }
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    /** A valid Add Phone Call Log form; {@code overrides} are key/value pairs that replace or add fields. */
    private String callJson(String name, Object... overrides) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("phone", "75675675675");
        body.put("callDate", "2026-09-21");
        body.put("nextFollowUpDate", "2026-09-30");
        body.put("callType", "INCOMING");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(body);
    }

    private UUID createCall(Cookie caller, String name, Object... overrides) throws Exception {
        var result = mockMvc.perform(post("/api/v1/phone-calls").cookie(caller)
                        .contentType(MediaType.APPLICATION_JSON).content(callJson(name, overrides)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    @Test
    void adminCanLogACallWithEveryField() throws Exception {
        mockMvc.perform(post("/api/v1/phone-calls").cookie(login(ADMIN)).contentType(MediaType.APPLICATION_JSON)
                        .content(callJson("Cleaning Services", "description", "Quote for term cleaning",
                                "callDuration", "5 min", "note", "Call back after 4pm", "callType", "outgoing")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Cleaning Services"))
                .andExpect(jsonPath("$.phone").value("75675675675"))
                .andExpect(jsonPath("$.callDate").value("2026-09-21"))
                .andExpect(jsonPath("$.nextFollowUpDate").value("2026-09-30"))
                .andExpect(jsonPath("$.callDuration").value("5 min"))
                .andExpect(jsonPath("$.callType").value("OUTGOING"))
                .andExpect(jsonPath("$.note").value("Call back after 4pm"));
    }

    @Test
    void createRejectsInvalidFormsWith400() throws Exception {
        Cookie admin = login(ADMIN);
        String[] badBodies = {
                "{\"name\":\"No phone\",\"callDate\":\"2026-09-21\",\"callType\":\"INCOMING\"}",
                callJson("No date", "callDate", null),
                callJson("No type", "callType", null),
                callJson("Bad type", "callType", "MISSED"),
                callJson("Bad phone", "phone", "call me"),
                callJson("Follow-up before call", "nextFollowUpDate", "2026-09-01"),
        };
        for (String bad : badBodies) {
            mockMvc.perform(post("/api/v1/phone-calls").cookie(admin).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        // Name and next follow-up are optional.
        createCall(admin, null, "nextFollowUpDate", null);
    }

    @Test
    void receptionistCanLogAndEditButNotDeleteAndTeacherIsLockedOut() throws Exception {
        Cookie receptionist = login(RECEPTIONIST);
        UUID id = createCall(receptionist, "Transport Agency");
        mockMvc.perform(put("/api/v1/phone-calls/" + id).cookie(receptionist).contentType(MediaType.APPLICATION_JSON)
                        .content(callJson("Transport Agency Ltd", "callType", "OUTGOING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Transport Agency Ltd"))
                .andExpect(jsonPath("$.callType").value("OUTGOING"));
        mockMvc.perform(delete("/api/v1/phone-calls/" + id).cookie(receptionist)).andExpect(status().isForbidden());

        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/phone-calls").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/phone-calls").cookie(teacher).contentType(MediaType.APPLICATION_JSON)
                        .content(callJson("Nope"))).andExpect(status().isForbidden());
    }

    @Test
    void listIsNewestFirstSearchableAndSortable() throws Exception {
        Cookie admin = login(ADMIN);
        createCall(admin, "Electricity Board", "callDate", "2026-09-14", "nextFollowUpDate", "2026-09-17",
                "phone", "634545353");
        createCall(admin, "Transport Agency", "callDate", "2026-09-18", "nextFollowUpDate", "2026-09-24",
                "callType", "OUTGOING");

        mockMvc.perform(get("/api/v1/phone-calls").cookie(admin))
                .andExpect(jsonPath("$.content[0].name").value("Transport Agency"))
                .andExpect(jsonPath("$.content[1].name").value("Electricity Board"));
        mockMvc.perform(get("/api/v1/phone-calls").cookie(admin).param("q", "634545"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Electricity Board"));
        mockMvc.perform(get("/api/v1/phone-calls").cookie(admin).param("sort", "name,asc"))
                .andExpect(jsonPath("$.content[0].name").value("Electricity Board"));
        mockMvc.perform(get("/api/v1/phone-calls").cookie(admin).param("sort", "createdByUserId,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCanDeleteACall() throws Exception {
        Cookie admin = login(ADMIN);
        UUID id = createCall(admin, "New Book-Stock");
        mockMvc.perform(delete("/api/v1/phone-calls/" + id).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/phone-calls/" + id).cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/phone-calls/" + id).cookie(admin)).andExpect(status().isNotFound());
    }
}
