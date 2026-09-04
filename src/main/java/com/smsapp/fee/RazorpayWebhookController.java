package com.smsapp.fee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsapp.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Razorpay server-to-server payment webhook. This endpoint is intentionally
 * public (no JWT -- Razorpay cannot present one), so the ONLY thing that makes an
 * inbound call trustworthy is the HMAC signature in {@code X-Razorpay-Signature}:
 * a missing or invalid signature is rejected with 400 before anything is read
 * from the body (plan section 7.2a/f). A verified {@code order.paid} event flips
 * the matching invoice to PAID; every other (verified) event is acknowledged and
 * ignored so Razorpay does not retry it.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);
    private static final String SIGNATURE_HEADER = "X-Razorpay-Signature";

    private final RazorpaySignatureVerifier signatureVerifier;
    private final FeeService feeService;
    private final ObjectMapper objectMapper;

    public RazorpayWebhookController(RazorpaySignatureVerifier signatureVerifier, FeeService feeService,
                                     ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.feeService = feeService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<Void> handle(@RequestBody(required = false) String payload,
                                       @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        if (payload == null || !signatureVerifier.isValid(payload, signature)) {
            // Do not read the body, do not touch any data -- this call is not from Razorpay.
            throw new ApiException("Invalid or missing webhook signature", HttpStatus.BAD_REQUEST);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new ApiException("Malformed webhook payload", HttpStatus.BAD_REQUEST);
        }

        String event = root.path("event").asText("");
        if (!"order.paid".equals(event) && !"payment.captured".equals(event)) {
            return ResponseEntity.ok().build();
        }

        JsonNode order = root.path("payload").path("order").path("entity");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        JsonNode notes = order.path("notes").hasNonNull(FeeService.NOTE_TENANT_ID)
                ? order.path("notes")
                : payment.path("notes");

        String tenantIdRaw = notes.path(FeeService.NOTE_TENANT_ID).asText(null);
        String invoiceIdRaw = notes.path(FeeService.NOTE_INVOICE_ID).asText(null);
        String orderId = order.path("id").asText(payment.path("order_id").asText(null));
        String paymentId = payment.path("id").asText(null);

        if (tenantIdRaw == null || invoiceIdRaw == null || orderId == null) {
            log.warn("Razorpay webhook '{}' lacked tenant/invoice/order references; acknowledged and ignored", event);
            return ResponseEntity.ok().build();
        }

        try {
            feeService.markInvoicePaid(UUID.fromString(tenantIdRaw), UUID.fromString(invoiceIdRaw), orderId, paymentId);
        } catch (IllegalArgumentException ex) {
            log.warn("Razorpay webhook '{}' had non-UUID references; acknowledged and ignored", event);
        }
        return ResponseEntity.ok().build();
    }
}
