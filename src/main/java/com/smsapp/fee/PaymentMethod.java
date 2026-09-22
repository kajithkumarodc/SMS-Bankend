package com.smsapp.fee;

import java.util.Set;

/**
 * How a {@link FeePayment} was collected. CASH/BANK_TRANSFER/CHEQUE/OTHER work with no external
 * service (plan: "no mandatory payment gateway"); ONLINE is reserved for payments settled through
 * the existing, optional Razorpay integration ({@link FeeService}) and is never chosen directly by
 * a manual-collection request. The DB CHECK constraint (V26) is the source of truth for the set.
 */
public final class PaymentMethod {

    public static final String CASH = "CASH";
    public static final String BANK_TRANSFER = "BANK_TRANSFER";
    public static final String CHEQUE = "CHEQUE";
    public static final String ONLINE = "ONLINE";
    public static final String OTHER = "OTHER";

    /** Methods a staff member may choose when manually collecting a payment -- excludes ONLINE. */
    private static final Set<String> MANUAL = Set.of(CASH, BANK_TRANSFER, CHEQUE, OTHER);

    private PaymentMethod() {
    }

    public static boolean isValidManual(String raw) {
        return MANUAL.contains(raw);
    }
}
