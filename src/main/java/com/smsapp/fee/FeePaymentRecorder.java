package com.smsapp.fee;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The single place a {@link FeePayment} row is ever inserted and an {@link Invoice}'s
 * {@code paidAmount}/{@code status} mutated in response -- both the manual collection path
 * ({@link FeeCollectionService#collectPayment}) and the existing Razorpay webhook/dev-simulate path
 * ({@link FeeService#markInvoicePaid}, {@link FeeService#simulatePaymentSuccess}) call this, so
 * "how much has actually been collected" is computed exactly one way and is never double-counted
 * (plan Phase 5 part H). The caller is responsible for holding a row lock on the invoice
 * ({@link InvoiceRepository#findByIdForUpdate}) for the duration of its transaction and for
 * validating {@code amount} against the current balance -- this method trusts the invoice object it
 * is handed and only re-asserts the non-negative-balance invariant as a defensive backstop.
 */
@Service
class FeePaymentRecorder {

    private final FeePaymentRepository feePaymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final AuditService auditService;

    FeePaymentRecorder(FeePaymentRepository feePaymentRepository, InvoiceRepository invoiceRepository,
                       AuditService auditService) {
        this.feePaymentRepository = feePaymentRepository;
        this.invoiceRepository = invoiceRepository;
        this.auditService = auditService;
    }

    @Transactional
    FeePayment record(Invoice invoice, BigDecimal amount, String method, String referenceNumber,
                      UUID collectedByUserId, String notes) {
        if (amount.compareTo(invoice.getBalance()) > 0) {
            throw new ApiException("Payment amount exceeds the outstanding balance", HttpStatus.BAD_REQUEST);
        }

        FeePayment payment = new FeePayment();
        payment.setInvoiceId(invoice.getId());
        payment.setType(PaymentType.PAYMENT);
        payment.setAmount(amount);
        payment.setMethod(method);
        payment.setReferenceNumber(referenceNumber);
        payment.setReceiptNumber(nextReceiptNumber());
        payment.setCollectedByUserId(collectedByUserId);
        payment.setNotes(notes);
        FeePayment saved = feePaymentRepository.save(payment);

        invoice.setPaidAmount(invoice.getPaidAmount().add(amount));
        InvoiceStatusCalculator.apply(invoice);
        invoiceRepository.save(invoice);

        auditService.log(AuditActions.FEE_PAYMENT_COLLECTED, AuditActions.FEE_PAYMENT, saved.getId(),
                Map.of("invoiceId", invoice.getId().toString(), "amount", amount.toPlainString(),
                        "method", method, "receiptNumber", saved.getReceiptNumber()));
        return saved;
    }

    String nextReceiptNumber() {
        return "RCPT-" + String.format("%06d", feePaymentRepository.nextReceiptSequence());
    }
}
