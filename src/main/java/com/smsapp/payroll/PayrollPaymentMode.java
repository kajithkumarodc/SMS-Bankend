package com.smsapp.payroll;

import java.util.Locale;
import java.util.Optional;

/** How a salary was paid: the Payment Mode choices on the Proceed To Pay form. */
public enum PayrollPaymentMode {
    CASH("Cash"),
    CHEQUE("Cheque"),
    BANK_TRANSFER("Transfer to Bank Account");

    private final String label;

    PayrollPaymentMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<PayrollPaymentMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
