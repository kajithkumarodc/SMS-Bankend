package com.smsapp.fee;

import com.smsapp.fee.FeeCollectionService.BulkAssignOutcome;
import com.smsapp.fee.FeeDtos.ApplyDiscountRequest;
import com.smsapp.fee.FeeDtos.BulkAssignRequest;
import com.smsapp.fee.FeeDtos.BulkAssignResponse;
import com.smsapp.fee.FeeDtos.CollectPaymentRequest;
import com.smsapp.fee.FeeDtos.CreateFeeDiscountRequest;
import com.smsapp.fee.FeeDtos.FeeDiscountResponse;
import com.smsapp.fee.FeeDtos.InvoiceResponse;
import com.smsapp.fee.FeeDtos.PaymentResponse;
import com.smsapp.fee.FeeDtos.ReceiptResponse;
import com.smsapp.fee.FeeDtos.ReversePaymentRequest;
import com.smsapp.fee.FeeDtos.StudentFeeStatementResponse;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Fee assignment/discount/collection/refund API (plan Phase 5 parts C-G, I) -- everything built on
 * top of the existing structures/invoices from {@link FeeController}. Staff-only; a parent's own
 * fee statement/receipt is exposed separately through the ownership-scoped
 * {@code com.smsapp.portal.PortalController}.
 */
@RestController
@RequestMapping("/api/v1")
public class FeeCollectionController {

    private final FeeCollectionService feeCollectionService;

    public FeeCollectionController(FeeCollectionService feeCollectionService) {
        this.feeCollectionService = feeCollectionService;
    }

    /** Bulk-assign one fee structure to many students at once (e.g. a whole class/section). 404 if the structure doesn't exist. */
    @PostMapping("/invoices/bulk-assign")
    @PreAuthorize(Permissions.HAS_FEE_ASSIGN)
    BulkAssignResponse bulkAssign(@Valid @RequestBody BulkAssignRequest request, Authentication authentication) {
        BulkAssignOutcome outcome = feeCollectionService.bulkAssign(
                request.feeStructureId(), request.studentIds(), actorId(authentication));
        return new BulkAssignResponse(outcome.results(), outcome.assignedCount(), request.studentIds().size());
    }

    // --- Discounts ---------------------------------------------------

    @GetMapping("/fee-discounts")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    List<FeeDiscountResponse> listDiscounts() {
        return feeCollectionService.listDiscounts().stream().map(FeeDiscountResponse::from).toList();
    }

    @PostMapping("/fee-discounts")
    @PreAuthorize(Permissions.HAS_FEE_DISCOUNT)
    ResponseEntity<FeeDiscountResponse> createDiscount(@Valid @RequestBody CreateFeeDiscountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FeeDiscountResponse.from(feeCollectionService.createDiscount(request)));
    }

    /** Apply a discount to an invoice. 404 if either doesn't exist, 409 if a payment has already been collected. */
    @PostMapping("/invoices/{invoiceId}/discount")
    @PreAuthorize(Permissions.HAS_FEE_DISCOUNT)
    InvoiceResponse applyDiscount(@PathVariable UUID invoiceId, @Valid @RequestBody ApplyDiscountRequest request) {
        return InvoiceResponse.from(feeCollectionService.applyDiscount(invoiceId, request.discountId()));
    }

    /** Apply the fee structure's configured late fee to an overdue invoice. Idempotent-by-rejection: 409 if already applied. */
    @PostMapping("/invoices/{invoiceId}/late-fee")
    @PreAuthorize(Permissions.HAS_FEE_EDIT)
    InvoiceResponse applyLateFee(@PathVariable UUID invoiceId) {
        return InvoiceResponse.from(feeCollectionService.applyLateFee(invoiceId));
    }

    // --- Collection ----------------------------------------------------

    /**
     * Collect a manual payment (CASH/BANK_TRANSFER/CHEQUE/OTHER) against an invoice. The server
     * computes and validates against the real outstanding balance -- a client-supplied amount
     * exceeding it is rejected, never trusted (plan part N).
     */
    @PostMapping("/invoices/{invoiceId}/payments")
    @PreAuthorize(Permissions.HAS_FEE_COLLECT)
    ResponseEntity<PaymentResponse> collectPayment(@PathVariable UUID invoiceId,
                                                   @Valid @RequestBody CollectPaymentRequest request,
                                                   Authentication authentication) {
        FeePayment payment = feeCollectionService.collectPayment(invoiceId, request.amount(), request.method(),
                request.referenceNumber(), request.notes(), actorId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment));
    }

    /** One invoice's full payment history (payments + any reversals), newest first. */
    @GetMapping("/invoices/{invoiceId}/payments")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    List<PaymentResponse> paymentsFor(@PathVariable UUID invoiceId) {
        return feeCollectionService.paymentsFor(invoiceId).stream().map(PaymentResponse::from).toList();
    }

    // --- Refund / reversal ---------------------------------------------

    @PostMapping("/payments/{paymentId}/reverse")
    @PreAuthorize(Permissions.HAS_FEE_REFUND)
    ResponseEntity<PaymentResponse> reversePayment(@PathVariable UUID paymentId,
                                                   @Valid @RequestBody ReversePaymentRequest request,
                                                   Authentication authentication) {
        FeePayment reversal = feeCollectionService.reversePayment(paymentId, request.reason(), actorId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(reversal));
    }

    // --- Receipt / statement --------------------------------------------

    @GetMapping("/payments/{paymentId}/receipt")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    ReceiptResponse receipt(@PathVariable UUID paymentId) {
        return feeCollectionService.receiptFor(paymentId, null);
    }

    /** Student Profile -> Fees tab (plan part I): totals + full invoice/payment history. 404 if the student doesn't exist. */
    @GetMapping("/students/{studentId}/fee-statement")
    @PreAuthorize(Permissions.HAS_FEE_VIEW)
    StudentFeeStatementResponse studentStatement(@PathVariable UUID studentId) {
        return feeCollectionService.studentStatement(studentId, null);
    }

    private static UUID actorId(Authentication authentication) {
        return UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
    }
}
