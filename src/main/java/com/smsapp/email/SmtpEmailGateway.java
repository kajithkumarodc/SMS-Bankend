package com.smsapp.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Plain SMTP via Spring Boot's auto-configured {@link JavaMailSender} ({@code spring.mail.*}) --
 * free/self-hosted, works unmodified against a local dev catcher (MailHog on port 1025 by default,
 * see application.yml) or any real SMTP relay. Never a paid email API.
 *
 * <p>Disabled entirely via {@code app.email.enabled=false} (the test profile's default -- no SMTP
 * server is available there) logs instead of sending. A send failure for any other reason is caught
 * and logged, never rethrown: the caller's database transaction must never fail because the mail
 * server is temporarily unreachable (plan Phase 4.5 part 16).
 */
@Component
class SmtpEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailGateway.class);

    private final JavaMailSender mailSender;
    private final EmailProperties properties;

    SmtpEmailGateway(JavaMailSender mailSender, EmailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(String toAddress, String subject, String body) {
        if (!properties.enabled()) {
            log.info("Email disabled (app.email.enabled=false) -- would have sent to={} subject={}", toAddress, subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.from());
            message.setTo(toAddress);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception ex) {
            // Deliberately broad: whatever the transport failure is, the caller's transaction must
            // still complete. This is the ONLY place that guarantee is enforced, so it must never leak.
            log.warn("Could not send email to={} subject={}", toAddress, subject, ex);
        }
    }
}
