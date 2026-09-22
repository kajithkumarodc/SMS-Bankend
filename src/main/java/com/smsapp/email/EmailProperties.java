package com.smsapp.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mapped in application.yml: {@code app.email.from <- EMAIL_FROM}, {@code app.email.enabled <-
 * EMAIL_ENABLED}. The actual SMTP host/port/credentials are Spring Boot's own {@code spring.mail.*}
 * properties (consumed by the auto-configured {@code JavaMailSender} {@link SmtpEmailGateway} wraps).
 */
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(String from, boolean enabled) {

    public EmailProperties {
        if (from == null || from.isBlank()) {
            from = "no-reply@school.local";
        }
    }
}
