package com.smsapp.fee;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The webhook signature check is the ONLY thing standing between a forged
 * "payment succeeded" POST and a PAID invoice (plan section 7.2a/f), so it gets
 * its own focused unit test: a correct HMAC passes, and every way of getting it
 * wrong -- tampered body, tampered signature, missing header, unconfigured
 * secret -- fails closed.
 */
class RazorpaySignatureVerifierTest {

    private static final String SECRET = "whsec_test_secret_value";
    private static final String BODY = "{\"event\":\"order.paid\",\"payload\":{\"order\":{\"entity\":{\"id\":\"order_1\"}}}}";

    private RazorpaySignatureVerifier verifier(String secret) {
        return new RazorpaySignatureVerifier(new RazorpayProperties("rzp_test_x", "keysecret", secret, "INR"));
    }

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsACorrectlySignedPayload() throws Exception {
        assertThat(verifier(SECRET).isValid(BODY, sign(BODY, SECRET))).isTrue();
    }

    @Test
    void rejectsAPayloadWhoseBodyWasTamperedAfterSigning() throws Exception {
        String signature = sign(BODY, SECRET);
        String tamperedBody = BODY.replace("order_1", "order_ATTACKER");

        assertThat(verifier(SECRET).isValid(tamperedBody, signature)).isFalse();
    }

    @Test
    void rejectsAForgedOrRandomSignature() {
        assertThat(verifier(SECRET).isValid(BODY, "deadbeef")).isFalse();
        assertThat(verifier(SECRET).isValid(BODY, "0".repeat(64))).isFalse();
    }

    @Test
    void rejectsAMissingOrBlankSignatureHeader() {
        assertThat(verifier(SECRET).isValid(BODY, null)).isFalse();
        assertThat(verifier(SECRET).isValid(BODY, "  ")).isFalse();
    }

    @Test
    void rejectsEverythingWhenNoWebhookSecretIsConfigured() throws Exception {
        // Even a signature that would be valid under some secret must not pass when
        // the server has no secret set -- fail closed, never fail open.
        assertThat(verifier(null).isValid(BODY, sign(BODY, SECRET))).isFalse();
        assertThat(verifier("").isValid(BODY, sign(BODY, SECRET))).isFalse();
    }

    @Test
    void rejectsASignatureMadeWithTheWrongSecret() throws Exception {
        assertThat(verifier(SECRET).isValid(BODY, sign(BODY, "some_other_secret"))).isFalse();
    }
}
