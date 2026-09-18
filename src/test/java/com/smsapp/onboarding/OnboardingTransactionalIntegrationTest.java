package com.smsapp.onboarding;

import com.smsapp.user.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Verifies {@link OnboardingService#registerSchool} is genuinely all-or-nothing:
 * a failure on the LAST write (linking the admin to the SCHOOL_ADMIN role) must
 * roll back everything written before it in the same transaction -- the tenant,
 * school, role and admin user rows created earlier in the same request. A real
 * Spring context + real PostgreSQL transaction is required to prove this (a
 * Mockito-only unit test has no transaction to roll back); only
 * {@link UserRoleRepository} is swapped for a throwing mock, forcing the failure
 * deterministically without weakening anything else in the flow.
 *
 * <p>Kept in its own test class (own Spring context) so the {@code @MockBean}
 * override here never leaks into {@link OnboardingIntegrationTest}'s happy-path
 * and validation tests, which need the real repository.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OnboardingTransactionalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRoleRepository userRoleRepository;

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
            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, audit_log CASCADE");
        }
        when(userRoleRepository.save(any())).thenThrow(new RuntimeException("simulated failure linking the role"));
    }

    @Test
    void aFailureOnTheLastWriteRollsBackTheEntireRegistration() throws SQLException {
        // The simulated RuntimeException has no ApiException/GlobalExceptionHandler mapping,
        // so it propagates out of mockMvc.perform(...) itself rather than coming back as a
        // response -- MockMvc only turns an exception into a response status when some
        // HandlerExceptionResolver claims it. The DB state below is the actual point of this
        // test, not the exception shape.
        assertThatThrownBy(() -> mockMvc.perform(post("/api/v1/onboarding/register-school")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolName\":\"Doomed School\",\"schoolIdentifier\":\"doomedschool\","
                                + "\"adminFullName\":\"Doomed Admin\",\"adminEmail\":\"admin@doomedschool.example\","
                                + "\"adminPassword\":\"Sup3rSecret\"}")))
                .hasRootCauseMessage("simulated failure linking the role");

        // The elevated (superuser, no RLS) connection is the unambiguous source of truth:
        // zero rows anywhere means the whole transaction genuinely rolled back, not merely
        // that RLS is hiding rows that still physically exist.
        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            assertThat(count(st, "SELECT count(*) FROM tenants WHERE identifier = 'doomedschool'")).isZero();
            assertThat(count(st, "SELECT count(*) FROM schools WHERE name = 'Doomed School'")).isZero();
            assertThat(count(st, "SELECT count(*) FROM users WHERE email = 'admin@doomedschool.example'")).isZero();
            assertThat(count(st, "SELECT count(*) FROM roles r JOIN tenants t ON t.id = r.tenant_id "
                    + "WHERE t.identifier = 'doomedschool'")).isZero();
        }
    }

    private static int count(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
