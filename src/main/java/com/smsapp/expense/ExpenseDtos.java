package com.smsapp.expense;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response payloads for the Expenses API. */
final class ExpenseDtos {

    private ExpenseDtos() {
    }

    /**
     * Body for {@code POST /api/v1/expenses} and {@code PUT /api/v1/expenses/{id}} -- the Add Expense form.
     * The document is uploaded separately.
     */
    record ExpenseRequest(
            @NotNull UUID expenseHeadId,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String invoiceNumber,
            @NotNull LocalDate expenseDate,
            @NotNull
            @DecimalMin(value = "0.01", message = "must be greater than zero")
            @DecimalMax(value = "9999999999.99", message = "is too large")
            @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimal places") BigDecimal amount,
            @Size(max = 2000) String description) {
    }

    record AttachmentInfo(String fileName, String contentType, long sizeBytes) {
    }

    record ExpenseResponse(
            UUID id,
            UUID expenseHeadId,
            String expenseHeadName,
            String name,
            String invoiceNumber,
            LocalDate expenseDate,
            BigDecimal amount,
            String description,
            /** Null when no document is attached. */
            AttachmentInfo attachment,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static ExpenseResponse from(Expense e, String expenseHeadName) {
            AttachmentInfo attachment = e.hasAttachment()
                    ? new AttachmentInfo(e.getAttachmentOriginalFilename(), e.getAttachmentContentType(),
                    e.getAttachmentSizeBytes() == null ? 0 : e.getAttachmentSizeBytes())
                    : null;
            return new ExpenseResponse(e.getId(), e.getExpenseHeadId(), expenseHeadName, e.getName(),
                    e.getInvoiceNumber(), e.getExpenseDate(), e.getAmount(), e.getDescription(), attachment,
                    e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    record ExpenseTotalResponse(BigDecimal total) {
    }

    /** Body for creating or renaming an expense head. */
    record ExpenseHeadRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {
    }

    record ExpenseHeadResponse(UUID id, String name, String description, boolean active) {

        static ExpenseHeadResponse from(ExpenseHead head) {
            return new ExpenseHeadResponse(head.getId(), head.getName(), head.getDescription(), head.isActive());
        }
    }
}
