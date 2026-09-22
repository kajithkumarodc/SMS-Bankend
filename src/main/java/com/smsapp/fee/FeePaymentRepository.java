package com.smsapp.fee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeePaymentRepository extends JpaRepository<FeePayment, UUID> {

    /** One invoice's full ledger (payments + any reversals), newest first -- the receipt/payment history. */
    List<FeePayment> findByInvoiceIdOrderByPaidAtDesc(UUID invoiceId);

    Optional<FeePayment> findByReceiptNumber(String receiptNumber);

    /** Double-reversal guard: has this PAYMENT row already been reversed? */
    boolean existsByReversesPaymentIdAndType(UUID reversesPaymentId, String type);

    /**
     * Next value of {@code fee_receipt_number_seq} (V26) -- a DB sequence avoids the race a
     * {@code count(*) + 1} scheme would have under concurrent collection (same reasoning as the
     * existing {@code enquiry_number_seq}).
     */
    @Query(value = "select nextval('fee_receipt_number_seq')", nativeQuery = true)
    long nextReceiptSequence();
}
