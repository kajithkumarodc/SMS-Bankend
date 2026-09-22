package com.smsapp.fee;

/** Every {@link FeePayment} row is one of these. The DB CHECK constraint (V26) is the source of truth. */
public final class PaymentType {

    public static final String PAYMENT = "PAYMENT";
    public static final String REVERSAL = "REVERSAL";

    private PaymentType() {
    }
}
