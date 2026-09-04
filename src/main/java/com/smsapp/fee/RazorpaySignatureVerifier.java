package com.smsapp.fee;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the {@code X-Razorpay-Signature} header on inbound webhooks.
 *
 * <p>Razorpay signs the exact raw request body with HMAC-SHA256 keyed by the
 * webhook secret and sends the lower-case hex digest in the header. Confirming
 * that signature is what stops a malicious client from POSTing a forged
 * "payment succeeded" call from their browser (plan section 7.2a/f). The
 * comparison is constant-time.
 */
@Component
class RazorpaySignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final RazorpayProperties properties;

    RazorpaySignatureVerifier(RazorpayProperties properties) {
        this.properties = properties;
    }

    /**
     * @return true only if {@code signatureHeader} is a valid signature of
     *         {@code rawBody} under the configured webhook secret. A missing
     *         header, a missing/blank configured secret, or any mismatch all
     *         return false -- fail closed.
     */
    boolean isValid(String rawBody, String signatureHeader) {
        String secret = properties.webhookSecret();
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()
                || secret == null || secret.isBlank()) {
            return false;
        }
        String expected = hmacHex(rawBody, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }
}
