package com.smsapp.fee;

import java.util.Map;

/**
 * Thin seam over Razorpay's server-side SDK. Only the operations this slice needs
 * are exposed, and nothing sensitive crosses it: we send an amount + our own
 * reference ids and get back a gateway order id. Kept as an interface so tests
 * (and any future gateway) can substitute an implementation without touching the
 * service logic (plan section 8, "gateway-abstraction layer").
 */
public interface RazorpayGateway {

    /**
     * Create a Razorpay Order for an amount already decided by an invoice.
     *
     * @param amountInPaise the charge in the smallest currency unit (Razorpay's contract)
     * @param currency      ISO currency code, e.g. {@code INR}
     * @param receipt       our own reference for reconciliation (the invoice id)
     * @param notes         key/value metadata Razorpay echoes back on the webhook
     * @return the created order's id (e.g. {@code order_MNZ...})
     */
    String createOrder(long amountInPaise, String currency, String receipt, Map<String, String> notes);

    /** The public key id the browser Checkout widget needs. Safe to expose (it is not the secret). */
    String keyId();
}
