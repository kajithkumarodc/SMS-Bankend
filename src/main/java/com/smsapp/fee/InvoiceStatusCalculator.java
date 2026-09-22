package com.smsapp.fee;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The single place {@code Invoice.status}/{@code paidAt} are derived from {@code paidAmount} vs.
 * {@code netAmount} -- called after every mutation of either (a payment, a reversal, a discount or
 * late fee changing the payable base), so the two can never drift apart.
 */
final class InvoiceStatusCalculator {

    private InvoiceStatusCalculator() {
    }

    static void apply(Invoice invoice) {
        BigDecimal paid = invoice.getPaidAmount();
        BigDecimal net = invoice.getNetAmount();
        // Checked first so a net amount of zero (e.g. a 100%-discounted invoice) is immediately PAID
        // even though paidAmount is also zero -- there is nothing left to collect either way.
        if (paid.compareTo(net) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
            if (invoice.getPaidAt() == null) {
                invoice.setPaidAt(OffsetDateTime.now());
            }
        } else if (paid.compareTo(BigDecimal.ZERO) <= 0) {
            invoice.setStatus(InvoiceStatus.PENDING);
            invoice.setPaidAt(null);
        } else {
            invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
            invoice.setPaidAt(null);
        }
    }
}
