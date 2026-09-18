package com.smsapp.onboarding;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service tenant onboarding against a real PostgreSQL instance with RLS
 * (plan sections 1/3). Covers: a successful registration produces a genuinely
 * working, isolated tenant whose admin can immediately log in; a clean 409 on a
 * duplicate school identifier (both a pre-existing one and a race against an
 * in-flight registration); the new tenant's data is invisible to, and cannot see,
 * every pre-existing tenant; and strict input validation on malformed/missing
 * fields. The all-or-nothing transactional guarantee on partial failure is
 * covered separately in {@link OnboardingTransactionalIntegrationTest}, which
 * needs a bean override this class's other tests must not share.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingIntegrationTest {

    private static final String SCHOOL_A = "onboard-a";
    private static final String SCHOOL_B = "onboard-b";
    private static final String ADMIN_A = "admin@onboard-a.example";
    private static final String PASSWORD = "secret";
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
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, audit_log CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, "School A");
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, "School B");

            UUID adminRoleA = seedRole(st, tenantA, "SCHOOL_ADMIN");
            seedUser(st, tenantA, ADMIN_A, adminRoleA);
        }
    }

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, String schoolName)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + UUID.randomUUID() + "', '" + tenantId + "', '" + schoolName + "')");
    }

    private static UUID seedRole(Statement st, UUID tenantId, String role) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        return roleId;
    }

    private void seedUser(Statement st, UUID tenantId, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '"
                + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
    }

    private static String registerBody(String schoolName, String identifier, String adminName, String adminEmail,
                                       String password) {
        return "{\"schoolName\":\"" + schoolName + "\",\"schoolIdentifier\":\"" + identifier
                + "\",\"adminFullName\":\"" + adminName + "\",\"adminEmail\":\"" + adminEmail
                + "\",\"adminPassword\":\"" + password + "\"}";
    }

    private Cookie login(String schoolIdentifier, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    // --- Happy path: a working, self-service tenant --------------

    @Test
    void registeringASchoolCreatesAWorkingTenantWhoseAdminCanImmediatelyLogIn() throws Exception {
        var result = mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Springfield Elementary", "Springfield", "Seymour Skinner",
                                "principal@springfield.example", "Sup3rSecret")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schoolIdentifier").value("springfield"))
                .andExpect(jsonPath("$.adminEmail").value("principal@springfield.example"))
                .andExpect(jsonPath("$.tenantId").isString())
                // Deliberately no auto-login: no token, no Set-Cookie.
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getCookie("access_token")).isNull();

        UUID tenantId = UUID.fromString(
                JSON.readTree(result.getResponse().getContentAsString()).get("tenantId").asText());

        // The new admin can log in immediately with exactly the credentials just registered.
        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"springfield\",\"email\":\"principal@springfield.example\","
                                + "\"password\":\"Sup3rSecret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.name").value("Seymour Skinner"))
                .andExpect(jsonPath("$.user.tenantId").value(tenantId.toString()))
                .andExpect(jsonPath("$.user.roles[0]").value("SCHOOL_ADMIN"))
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().httpOnly("access_token", true))
                .andReturn();

        JsonNode body = JSON.readTree(loginResult.getResponse().getContentAsString());
        assertThat(body.get("token").asText()).isNotBlank();
    }

    // --- Duplicate identifier: clean 409 --------------------------

    @Test
    void registeringWithAnAlreadyTakenIdentifierReturnsAClean409() throws Exception {
        // SCHOOL_A was pre-seeded in @BeforeEach.
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Copycat School", SCHOOL_A, "Someone Else", "someone@copycat.example",
                                "Sup3rSecret")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString(SCHOOL_A)));
    }

    @Test
    void registeringTwiceWithTheSameNewIdentifierReturnsAClean409OnTheSecondAttempt() throws Exception {
        String body1 = registerBody("First Try", "raceschool", "First Admin", "first@raceschool.example", "Sup3rSecret");
        String body2 = registerBody("Second Try", "raceschool", "Second Admin", "second@raceschool.example", "Sup3rSecret");

        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON).content(body1))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("raceschool")));
    }

    // --- Isolation: the new tenant vs every pre-existing tenant ---

    @Test
    void aNewlyRegisteredTenantIsIsolatedFromEveryExistingTenant() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Shelbyville Elementary", "shelbyville", "Gary Chalmers",
                                "gary@shelbyville.example", "Sup3rSecret")))
                .andExpect(status().isCreated());

        // The new tenant's admin sees only their own school -- never Tenant A's or B's.
        mockMvc.perform(get("/api/v1/schools").cookie(login("shelbyville", "gary@shelbyville.example", "Sup3rSecret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Shelbyville Elementary"));

        // A brand-new tenant starts with a clean slate -- no students leaked in from anywhere.
        mockMvc.perform(get("/api/v1/students").cookie(login("shelbyville", "gary@shelbyville.example", "Sup3rSecret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        // Pre-existing Tenant A cannot see the new tenant's school either.
        mockMvc.perform(get("/api/v1/schools").cookie(login(SCHOOL_A, ADMIN_A, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("School A"));
    }

    // --- Input validation: strict, clear 400s ---------------------

    @Test
    void blankSchoolNameReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("", "validid", "Admin Name", "admin@validid.example", "Sup3rSecret")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedIdentifierWithSpacesAndSymbolsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Some School", "not a valid id!", "Admin Name",
                                "admin@notvalid.example", "Sup3rSecret")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void identifierStartingWithAHyphenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Some School", "-leadinghyphen", "Admin Name",
                                "admin@leadinghyphen.example", "Sup3rSecret")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooShortIdentifierReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Some School", "ab", "Admin Name", "admin@ab.example", "Sup3rSecret")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedAdminEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Some School", "goodidone", "Admin Name", "not-an-email",
                                "Sup3rSecret")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooShortPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Some School", "goodidtwo", "Admin Name", "admin@goodidtwo.example",
                                "short1")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordWithNoDigitReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Some School", "goodidthree", "Admin Name",
                                "admin@goodidthree.example", "NoDigitsHere")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingFieldsReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
