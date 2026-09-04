package com.smsapp.fee;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the fee-management API. Entities are never exposed directly (plan section 7.1d). */
final class FeeDtos {

    private FeeDtos() {
    }

    record CreateFeeStructureRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 150) String name,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotNull LocalDate dueDate) {
    }

    record FeeStructureResponse(
            UUID id,
            UUID schoolId,
            String name,
            BigDecimal amount,
            LocalDate dueDate,
            OffsetDateTime createdAt) {

        static FeeStructureResponse from(FeeStructure structure) {
            return new FeeStructureResponse(structure.getId(), structure.getSchoolId(), structure.getName(),
                    structure.getAmount(), structure.getDueDate(), structure.getCreatedAt());
        }
    }

    /** Generate an invoice for one student against one fee structure. The amount is copied from the structure. */
    record CreateInvoiceRequest(
            @NotNull UUID studentId,
            @NotNull UUID feeStructureId) {
    }

    record InvoiceResponse(
            UUID id,
            UUID studentId,
            UUID feeStructureId,
            BigDecimal amount,
            String status,
            String razorpayOrderId,
            String razorpayPaymentId,
            OffsetDateTime createdAt,
            OffsetDateTime paidAt) {

        static InvoiceResponse from(Invoice invoice) {
            return new InvoiceResponse(invoice.getId(), invoice.getStudentId(), invoice.getFeeStructureId(),
                    invoice.getAmount(), invoice.getStatus(), invoice.getRazorpayOrderId(),
                    invoice.getRazorpayPaymentId(), invoice.getCreatedAt(), invoice.getPaidAt());
        }
    }

    /**
     * The safe slice of a Razorpay Order the browser's Checkout widget needs. Never
     * carries card, bank or any raw payment data (plan section 7.2a).
     */
    record CheckoutResponse(
            UUID invoiceId,
            String razorpayOrderId,
            String razorpayKeyId,
            long amountInPaise,
            String currency) {
    }
}
