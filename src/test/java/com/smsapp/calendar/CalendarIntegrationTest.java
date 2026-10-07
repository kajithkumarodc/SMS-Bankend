package com.smsapp.calendar;

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
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Annual Calendar against a real PostgreSQL instance: types, entries and CALENDAR_* gating. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarIntegrationTest {

    private static final String ADMIN = "cal-admin@school.example";
    private static final String VIEWER = "cal-viewer@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Cookie admin;
    private String holidayId;
    private String eventsId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, staff_profiles, calendar_events, holiday_types, "
                    + "departments, designations CASCADE");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID viewerRole = seedUser(st, VIEWER, "TEACHER");
            for (String name : new String[] {"CALENDAR_VIEW", "CALENDAR_MANAGE"}) {
                st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
                st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + adminRole + "', id FROM permissions WHERE name = '" + name + "'");
            }
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + viewerRole + "', id FROM permissions WHERE name = 'CALENDAR_VIEW'");
        }
        admin = login(ADMIN);
        holidayId = idOf(postJson("/api/v1/calendar/types", Map.of("name", "Holiday")));
        eventsId = idOf(postJson("/api/v1/calendar/types", Map.of("name", "EVENTS")));
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', 'Joe Black')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return roleId;
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("access_token");
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url).cookie(admin).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private ResultActions putJson(String url, Object body) throws Exception {
        return mockMvc.perform(put(url).cookie(admin).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private String idOf(ResultActions result) throws Exception {
        return JSON.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private Map<String, Object> event(String typeId, String from, String to, String description, boolean frontSite) {
        var body = new LinkedHashMap<String, Object>();
        body.put("typeId", typeId);
        body.put("fromDate", from);
        body.put("toDate", to);
        body.put("description", description);
        body.put("frontSite", frontSite);
        return body;
    }

    @Test
    void typesFollowTheNameRulesAndAreProtectedWhileUsed() throws Exception {
        postJson("/api/v1/calendar/types", Map.of("name", "holiday")).andExpect(status().isConflict());
        postJson("/api/v1/calendar/types", Map.of("name", " ")).andExpect(status().isBadRequest());
        putJson("/api/v1/calendar/types/" + eventsId, Map.of("name", "HOLIDAY")).andExpect(status().isConflict());
        putJson("/api/v1/calendar/types/" + eventsId, Map.of("name", "Events")).andExpect(status().isOk());
        postJson("/api/v1/calendar/events", event(holidayId, "2026-10-26", "2026-10-31", "Yoga Day", true)).andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/calendar/types/" + holidayId).cookie(admin)).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/v1/calendar/types/" + eventsId).cookie(admin)).andExpect(status().isNoContent());
    }

    @Test
    void entriesAreCreatedEditedFilteredAndDeleted() throws Exception {
        String yoga = idOf(postJson("/api/v1/calendar/events", event(holidayId, "2026-10-26", "2026-10-31", "  Yoga Day ", true)));
        postJson("/api/v1/calendar/events", event(eventsId, "2026-11-02", "2026-11-02", "Science Fair", false)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.typeName").value("EVENTS")).andExpect(jsonPath("$.createdByName").value("Joe Black"))
                .andExpect(jsonPath("$.frontSite").value(false));
        mockMvc.perform(get("/api/v1/calendar/events").cookie(admin)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].description").value("Science Fair")).andExpect(jsonPath("$[1].description").value("Yoga Day"));
        mockMvc.perform(get("/api/v1/calendar/events").cookie(admin).param("typeId", holidayId))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].fromDate").value("2026-10-26"));
        putJson("/api/v1/calendar/events/" + yoga, event(eventsId, "2026-10-26", "2026-11-01", "Yoga Week", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.typeName").value("EVENTS")).andExpect(jsonPath("$.toDate").value("2026-11-01"));
        mockMvc.perform(delete("/api/v1/calendar/events/" + yoga).cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/calendar/events/" + yoga).cookie(admin)).andExpect(status().isNotFound());
    }

    @Test
    void entriesAreValidated() throws Exception {
        postJson("/api/v1/calendar/events", event(holidayId, "2026-10-31", "2026-10-26", "Backwards", true)).andExpect(status().isBadRequest());
        postJson("/api/v1/calendar/events", event(holidayId, "2026-10-26", "2026-10-31", " ", true)).andExpect(status().isBadRequest());
        postJson("/api/v1/calendar/events", event(UUID.randomUUID().toString(), "2026-10-26", "2026-10-31", "Unknown type", true)).andExpect(status().isNotFound());
        postJson("/api/v1/calendar/events", event(holidayId, "2026-10-26", "2026-10-26", "One day", true)).andExpect(status().isCreated());
    }

    @Test
    void viewersCanReadButNotChange() throws Exception {
        Cookie viewer = login(VIEWER);
        mockMvc.perform(get("/api/v1/calendar/types").cookie(viewer)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/calendar/events").cookie(viewer)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/calendar/types").cookie(viewer).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/calendar/events").cookie(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeId\":\"" + holidayId + "\",\"fromDate\":\"2026-10-26\",\"toDate\":\"2026-10-26\",\"description\":\"x\"}")).andExpect(status().isForbidden());
    }
}
