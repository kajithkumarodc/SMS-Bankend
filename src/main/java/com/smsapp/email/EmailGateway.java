package com.smsapp.email;

/**
 * Thin seam over however email actually gets sent (plan Phase 4.5 parts 14/16) -- same
 * gateway-abstraction reasoning as {@code com.smsapp.fee.RazorpayGateway}: business code depends on
 * this interface, never on a mail-library class, so the transport can change (or be mocked in
 * tests) without touching callers. The only implementation is plain SMTP ({@link SmtpEmailGateway}),
 * free/self-hosted-friendly (works against a local dev catcher like MailHog or any real relay) --
 * never a paid provider.
 *
 * <p>Every implementation MUST swallow its own failures (log, never throw): a database transaction
 * must never fail because an SMTP server is temporarily unreachable (plan part 16).
 */
public interface EmailGateway {

    void send(String toAddress, String subject, String body);
}
