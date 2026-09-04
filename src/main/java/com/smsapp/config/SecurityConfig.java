package com.smsapp.config;

import com.smsapp.auth.AuthCookieFactory;
import com.smsapp.auth.CookieBearerTokenResolver;
import com.smsapp.tenant.TenantRequestFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Collection;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;

    public SecurityConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthCookieFactory authCookieFactory) throws Exception {
        http
            .cors(Customizer.withDefaults())
            // CSRF token protection is intentionally disabled (see ADR
            // docs/adr/002-csrf-mitigation-strategy.md). This is a stateless
            // resource server: the access token lives in an httpOnly cookie set
            // with SameSite=Strict (AuthCookieFactory), so a browser will not
            // attach it to any cross-site request -- which is exactly the vector
            // CSRF tokens defend against. There is no server-side session to
            // ride. Revisit this if a non-browser client (mobile app, server-to
            // -server) is added, since SameSite is a browser-only guarantee.
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/logout", "/actuator/health",
                    "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .permitAll()
                // Razorpay's server-to-server payment webhook cannot present a JWT.
                // It is authenticated instead by its HMAC signature, which the
                // handler verifies before touching any data (see RazorpayWebhookController).
                .requestMatchers("/api/v1/webhooks/razorpay")
                .permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .bearerTokenResolver(new CookieBearerTokenResolver(authCookieFactory.cookieName()))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .addFilterAfter(new TenantRequestFilter(), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Maps the JWT {@code roles} claim (e.g. {@code ["SCHOOL_ADMIN"]}) to Spring
     * Security {@code ROLE_*} authorities so {@code @PreAuthorize("hasRole(...)")}
     * works for method-level RBAC (plan section 3/6).
     */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::rolesToAuthorities);
        return converter;
    }

    private static Collection<GrantedAuthority> rolesToAuthorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Treated as patterns (setAllowedOriginPatterns, not setAllowedOrigins) so a
        // dev entry like "http://localhost:5173" also covers the equivalent
        // "http://127.0.0.1:5173" when configured, and wildcards such as
        // "http://localhost:*" are allowed. A mismatched Origin is rejected by the
        // CorsFilter with 403 ("Invalid CORS request") BEFORE the security rules run,
        // which looks exactly like an auth failure on an otherwise-permitAll endpoint.
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
