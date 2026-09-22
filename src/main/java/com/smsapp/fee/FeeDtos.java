package com.smsapp.fee;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Request/response payloads for the fee-management API. Entities are never exposed directly (plan
 * section 7.1d). Public, with the specific response records the parent portal needs also public --
 * same "widen only what crosses the package boundary" precedent as {@code StudentDtos} (Phase 2).
 */
public final class FeeDtos {

    private FeeDtos() {
    }

    // --- Fee types (plan Phase 5 part B: configurable, not hardcoded) -------

    record CreateFeeTypeRequest(@NotBlank @Size(max = 100) String name) {
    }

    record FeeTypeResponse(UUID id, String name, boolean active) {

        static FeeTypeResponse from(FeeType type) {
            return new FeeTypeResponse(type.getId(), type.getName(), type.isActive());
        }
    }

    // --- Fee structures ------------------------------------------------

    /**
     * Either {@code items} (a class's Application/Admission/Term I-IV breakdown,
     * matching a real fee-structure sheet) or a flat {@code amount} must be given.
     * When {@code items} is present and non-empty it wins: {@code amount} is
     * computed server-side as its sum and any value passed here is ignored.
     * {@code frequency} defaults to ONE_TIME when omitted.
     */
    record CreateFeeStructureRequest(
            @NotNull UUID schoolId,
            UUID classId,
            @NotBlank @Size(max = 20) String academicYear,
            @NotBlank @Size(max = 150) String name,
            @DecimalMin(value = "0.01") BigDecimal amount,
            @NotNull LocalDate dueDate,
            String frequency,
            @DecimalMin(value = "0.01") BigDecimal lateFeeAmount,
            List<LineItemRequest> items) {
    }

    /** One Application/Admission/Term-N/Other line; {@code sequenceOrder} is derived from list position. */
    record LineItemRequest(
            @NotBlank String category,
            @Size(max = 150) String label,
            UUID feeTypeId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount) {
    }

    record FeeStructureItemResponse(
            UUID id,
            String category,
            String label,
            UUID feeTypeId,
            String feeTypeName,
            BigDecimal amount,
            int sequenceOrder) {

        static FeeStructureItemResponse from(FeeStructureItem item, String feeTypeName) {
            return new FeeStructureItemResponse(item.getId(), item.getCategory(), item.getLabel(),
                    item.getFeeTypeId(), feeTypeName, item.getAmount(), item.getSequenceOrder());
        }
    }

    record FeeStructureResponse(
            UUID id,
            UUID schoolId,
            UUID classId,
            String academicYear,
            String name,
            BigDecimal amount,
            LocalDate dueDate,
            String frequency,
            BigDecimal lateFeeAmount,
            String status,
            List<FeeStructureItemResponse> items,
            OffsetDateTime createdAt) {

        static FeeStructureResponse from(FeeStructure structure, List<FeeStructureItemResponse> items) {
            return new FeeStructureResponse(
                    structure.getId(),
                    structure.getSchoolId(),
                    structure.getClassId(),
                    structure.getAcademicYear(),
                    structure.getName(),
                    structure.getAmount(),
                    structure.getDueDate(),
                    structure.getFrequency(),
                    structure.getLateFeeAmount(),
                    structure.getStatus(),
                    items,
                    structure.getCreatedAt());
        }
    }

    // --- Discounts (plan Phase 5 part D) --------------------------------

    record CreateFeeDiscountRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank String discountType,
            @NotNull @DecimalMin(value = "0.01") BigDecimal value,
            UUID feeStructureId,
            LocalDate validFrom,
            LocalDate validTo) {
    }

    record FeeDiscountResponse(
            UUID id,
            String name,
            String discountType,
            BigDecimal value,
            UUID feeStructureId,
            LocalDate validFrom,
            LocalDate validTo,
            String status,
            OffsetDateTime createdAt) {

        static FeeDiscountResponse from(FeeDiscount discount) {
            return new FeeDiscountResponse(discount.getId(), discount.getName(), discount.getDiscountType(),
                    discount.getValue(), discount.getFeeStructureId(), discount.getValidFrom(),
                    discount.getValidTo(), discount.getStatus(), discount.getCreatedAt());
        }
    }

    record ApplyDiscountRequest(@NotNull UUID discountId) {
    }

    // --- Invoices / assignment (plan Phase 5 part C) --------------------

    /** Generate an invoice for one student against one fee structure. The amount is copied from the structure. */
    record CreateInvoiceRequest(
            @NotNull UUID studentId,
            @NotNull UUID feeStructureId) {
    }

    /** Bulk-assign one fee structure to every id in {@code studentIds}; a student already invoiced for it is skipped, not duplicated. */
    record BulkAssignRequest(
            @NotNull UUID feeStructureId,
            @NotEmpty Set<UUID> studentIds) {
    }

    record BulkAssignResult(UUID studentId, boolean assigned, String reason) {
    }

    record BulkAssignResponse(List<BulkAssignResult> results, int assignedCount, int requestedCount) {
    }

    record InvoiceResponse(
            UUID id,
            UUID studentId,
            UUID feeStructureId,
            BigDecimal amount,
            BigDecimal discountAmount,
            BigDecimal netAmount,
            BigDecimal lateFeeAmount,
            boolean lateFeeApplied,
            BigDecimal paidAmount,
            BigDecimal balance,
            String status,
            String razorpayOrderId,
            String razorpayPaymentId,
            OffsetDateTime createdAt,
            OffsetDateTime paidAt) {

        static InvoiceResponse from(Invoice invoice) {
            return new InvoiceResponse(invoice.getId(), invoice.getStudentId(), invoice.getFeeStructureId(),
                    invoice.getAmount(), invoice.getDiscountAmount(), invoice.getNetAmount(),
                    invoice.getLateFeeAmount(), invoice.isLateFeeApplied(), invoice.getPaidAmount(),
                    invoice.getBalance(), invoice.getStatus(), invoice.getRazorpayOrderId(),
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

    // --- Collection / payments (plan Phase 5 part E/F) ------------------

    /** Collect a manual payment (CASH/BANK_TRANSFER/CHEQUE/OTHER) against an invoice's current balance. */
    record CollectPaymentRequest(
            @NotNull @Positive BigDecimal amount,
            @NotBlank String method,
            @Size(max = 255) String referenceNumber,
            @Size(max = 500) String notes) {
    }

    public record PaymentResponse(
            UUID id,
            UUID invoiceId,
            String type,
            BigDecimal amount,
            String method,
            String referenceNumber,
            String receiptNumber,
            UUID collectedByUserId,
            String notes,
            UUID reversesPaymentId,
            String reason,
            OffsetDateTime paidAt) {

        static PaymentResponse from(FeePayment payment) {
            return new PaymentResponse(payment.getId(), payment.getInvoiceId(), payment.getType(),
                    payment.getAmount(), payment.getMethod(), payment.getReferenceNumber(),
                    payment.getReceiptNumber(), payment.getCollectedByUserId(), payment.getNotes(),
                    payment.getReversesPaymentId(), payment.getReason(), payment.getPaidAt());
        }
    }

    record ReversePaymentRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** Everything a printable receipt needs, resolved server-side (plan Phase 5 part F). */
    public record ReceiptResponse(
            PaymentResponse payment,
            UUID invoiceId,
            String schoolName,
            String studentName,
            String admissionNumber,
            String className,
            String sectionName,
            String academicYear,
            String feeStructureName,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            BigDecimal lateFeeAmount,
            BigDecimal balanceAfter,
            String collectedByName) {
    }

    // --- Student fee statement (plan Phase 5 part I) ---------------------

    public record StudentFeeStatementResponse(
            UUID studentId,
            BigDecimal totalAssigned,
            BigDecimal totalDiscount,
            BigDecimal totalPayable,
            BigDecimal totalPaid,
            BigDecimal totalBalance,
            List<InvoiceStatementLine> invoices) {
    }

    public record InvoiceStatementLine(
            UUID invoiceId,
            String feeStructureName,
            LocalDate dueDate,
            BigDecimal amount,
            BigDecimal discountAmount,
            BigDecimal netAmount,
            BigDecimal paidAmount,
            BigDecimal balance,
            String status,
            boolean overdue,
            List<PaymentResponse> payments) {
    }
}
