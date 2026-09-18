package com.smsapp.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a Spring Boot/Security interaction MockMvc cannot reproduce:
 * Boot's default error handling resolves an unhandled MVC exception (e.g. a Bean
 * Validation failure) by internally forwarding the request to {@code /error}, and
 * Boot registers the security filter chain for that FORWARD dispatch too, not just
 * the original REQUEST. Before {@code /error} was added to the {@code permitAll}
 * list in {@code SecurityConfig}, an unauthenticated caller's validation failure on
 * ANY permitAll endpoint came back as an empty 401 (masking the real 400) --
 * discovered by smoke-testing {@code /register-school} against a live server, not
 * by the (all-passing) MockMvc-based {@link OnboardingIntegrationTest}, whose
 * {@code TestDispatcherServlet} does not perform a real forward and so cannot
 * catch this regressing. This needs a genuinely running embedded server
 * ({@code WebEnvironment.RANDOM_PORT} + a real HTTP client) to mean anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OnboardingRealServerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

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
    }

    private static HttpEntity<String> jsonBody(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void aValidationFailureOnAPermitAllEndpointReturns400NotAMasked401() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/onboarding/register-school",
                jsonBody("{\"schoolName\":\"X\",\"schoolIdentifier\":\"realservertest\",\"adminFullName\":\"X\","
                        + "\"adminEmail\":\"x@realservertest.example\",\"adminPassword\":\"weak\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theSameRegressionIsCoveredOnThePreExistingLoginEndpointToo() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/login", jsonBody("{}"), String.class);

        // Bean Validation should reject the blank fields with 400, not an authentication-layer 401.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aSuccessfulRegistrationStillWorksEndToEndOnARealEmbeddedServer() {
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/onboarding/register-school",
                jsonBody("{\"schoolName\":\"Real Server School\",\"schoolIdentifier\":\"realserverok\","
                        + "\"adminFullName\":\"Real Admin\",\"adminEmail\":\"admin@realserverok.example\","
                        + "\"adminPassword\":\"Sup3rSecret1\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
