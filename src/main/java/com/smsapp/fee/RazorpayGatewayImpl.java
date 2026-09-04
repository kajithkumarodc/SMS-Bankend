package com.smsapp.fee;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.smsapp.common.ApiException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Real Razorpay integration. The {@link RazorpayClient} is created lazily on
 * first use so the application still starts (and tests still run) when no
 * credentials are configured -- only an actual checkout needs them.
 */
@Component
class RazorpayGatewayImpl implements RazorpayGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGatewayImpl.class);

    private final RazorpayProperties properties;
    private volatile RazorpayClient client;

    RazorpayGatewayImpl(RazorpayProperties properties) {
        this.properties = properties;
    }

    @Override
    public String createOrder(long amountInPaise, String currency, String receipt, Map<String, String> notes) {
        JSONObject request = new JSONObject();
        request.put("amount", amountInPaise);
        request.put("currency", currency);
        request.put("receipt", receipt);
        request.put("payment_capture", true);
        JSONObject noteJson = new JSONObject();
        notes.forEach(noteJson::put);
        request.put("notes", noteJson);

        try {
            Order order = client().orders.create(request);
            return order.get("id");
        } catch (RazorpayException ex) {
            log.warn("Razorpay order creation failed for receipt={}", receipt, ex);
            throw new ApiException("Could not start the payment. Please try again.", HttpStatus.BAD_GATEWAY);
        }
    }

    @Override
    public String keyId() {
        return properties.keyId();
    }

    private RazorpayClient client() {
        RazorpayClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    if (properties.keyId() == null || properties.keyId().isBlank()
                            || properties.keySecret() == null || properties.keySecret().isBlank()) {
                        throw new ApiException("Online payments are not configured.", HttpStatus.SERVICE_UNAVAILABLE);
                    }
                    try {
                        local = new RazorpayClient(properties.keyId(), properties.keySecret());
                    } catch (RazorpayException ex) {
                        throw new ApiException("Online payments are not configured.", HttpStatus.SERVICE_UNAVAILABLE);
                    }
                    client = local;
                }
            }
        }
        return local;
    }
}
