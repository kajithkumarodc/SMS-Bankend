package com.smsapp.fee;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay credentials -- sourced from environment variables only, never
 * hard-coded (plan section 7.2d / 7.3). Mapped in application.yml:
 * {@code razorpay.key-id <- RAZORPAY_KEY_ID}, etc.
 */
@ConfigurationProperties(prefix = "razorpay")
public record RazorpayProperties(
        String keyId,
        String keySecret,
        /** Separate secret configured on the Razorpay dashboard webhook -- used only to verify inbound webhook signatures. */
        String webhookSecret,
        String currency) {

    public RazorpayProperties {
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
    }
}
